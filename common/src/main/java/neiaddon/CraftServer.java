package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import neiaddon.Craft.ChainAnswer;
import neiaddon.Craft.ChainStep;
import neiaddon.Craft.CraftChainRequest;
import neiaddon.Craft.CraftChainResult;
import neiaddon.Craft.CraftRequest;
import neiaddon.Craft.CraftResult;
import neiaddon.Craft.Reason;
import neiaddon.platform.Platform;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

/**
 * The server half of autocrafting: crafts one recipe a number of times at the player's open vanilla crafting table or
 * 2x2 inventory grid, at Tinkers' Crafting Station, or at an AE2 crafting terminal ({@link Ae2CraftServer}), all inside
 * one server tick (see {@link Craft}). A chain request runs several such steps in that same tick, one after the other,
 * and stops at the first step that is not done. The chain logic stays on the client; this only does the "fill the grid
 * and craft N times" step, from the player's own inventory.
 *
 * <p> Ars Nouveau's crafting lectern ({@link #isLectern}) keeps its grid and result in its tile, and its storage
 * outside the menu's slots. Its fill is the lectern's own transfer, and the take is the vanilla take, whose result slot
 * makes the lectern craft (see {@link Job#runLectern}).
 *
 * <p>
 * Taking the result copies the result-slot branch of {@code CraftingMenu.quickMoveStack} (and
 * {@code InventoryMenu.quickMoveStack}): {@code onCraftedBy}, move the stack into the inventory from the last slot
 * backwards, {@code Slot.onQuickCraft}, then {@code ResultSlot.onTake}, which does the remainders, the tool damage, the
 * advancements and, on Forge, the crafting event. The client's item counts are never trusted: everything is read from
 * the server's own menu. Common code: no loader classes, and nothing here touches the client.
 *
 * <p>
 * The station keeps its result without storing it, and it takes the ingredients in its own {@code quickMoveStack}. So
 * there the station's take does the craft, and the grid is read as a copy for the recipe's check. The station is found
 * by class name (the addon has no compile-time dependency on Tinkers) and only with no side inventory beside it.
 *
 * <p>
 * Sophisticated Storage's crafting upgrade is read by name and reflection too (see {@link #isStorageMenu}). Its grid
 * and result are slots the menu keeps outside its slot list, and its storage is the menu's other slots, so the fill
 * and the put-back use both the player's inventory and the storage, and the take is the menu's own quickMoveStack.
 */
public final class CraftServer {

    private static final int PER_SECOND = 40;
    /** The player's own slots a craft draws from and puts the result into: the 27 inventory slots and the hotbar. */
    private static final int MAIN_SLOTS = 36;

    /** Tinkers' Crafting Station's menu and result slot, by class name. */
    private static final String STATION_MENU = "slimeknights.tconstruct.tables.menu.CraftingStationContainerMenu";
    private static final String STATION_RESULT =
        "slimeknights.tconstruct.tables.menu.slot.PlayerSensitiveLazyResultSlot";
    private static final String STATION_RESULT_BASE = "slimeknights.tconstruct.tables.menu.slot.LazyResultSlot";
    /** The station's slots in order: its 3x3 grid (0-8), its result (9), then the player's 36 slots (10-45). */
    private static final int STATION_SIDE = 3;
    private static final int STATION_RESULT_INDEX = 9;
    private static final int STATION_SLOTS = 46;

    /** Sophisticated Storage's storage menu base and its crafting upgrade, by class name (see {@link #isStorageMenu}).
     */
    private static final String STORAGE_MENU =
        "net.p3pp3rf1y.sophisticatedcore.common.gui.StorageContainerMenuBase";
    private static final String STORAGE_CRAFTING =
        "net.p3pp3rf1y.sophisticatedcore.upgrades.crafting.CraftingUpgradeContainer";
    /** The crafting upgrade's slots in order: its nine grid cells (0-8), then its result (9). */
    private static final int STORAGE_CELLS = 9;
    private static final int STORAGE_SLOTS = 10;

    /** Ars Nouveau's crafting lectern: its terminal menu and its tile, by class name (see {@link #isLectern}). */
    private static final String LECTERN_MENU = "com.hollingsworth.arsnouveau.client.container.CraftingTerminalMenu";
    private static final String LECTERN_TILE = "com.hollingsworth.arsnouveau.common.block.tile.CraftingLecternTile";
    /** The lectern's slots in order: its result (0), its 3x3 cells (1-9), then the player's 36 slots (10-45). */
    private static final int LECTERN_CELLS = 9;
    private static final int LECTERN_SLOTS = 46;

    /** Per player: {last request's tick, start of the rate window, requests in the window}. */
    private static final Map<UUID, long[]> LIMITS = new HashMap<>();
    /** The server the one warning was logged for (an integrated server starts again in the same game run). */
    private static MinecraftServer warnedFor;
    /** The same for the crafting upgrade's path, which has its own warning. */
    private static MinecraftServer storageWarnedFor;
    /** The same for the lectern's path. */
    private static MinecraftServer lecternWarnedFor;

    private CraftServer() {}

    /**
     * Whether the menu is Tinkers' Crafting Station as the server crafts at it: the station's class name, its 46 slots
     * (so no side inventory) and a result slot of the station's kind. Read by name only, on both sides.
     */
    public static boolean isStation(AbstractContainerMenu menu) {
        return menu.getClass().getName().equals(STATION_MENU) && menu.slots.size() == STATION_SLOTS
            && isStationResult(menu.slots.get(STATION_RESULT_INDEX));
    }

    /** Whether the slot is the station's result: a PlayerSensitiveLazyResultSlot, or any subclass of LazyResultSlot. */
    public static boolean isStationResult(Slot slot) {
        if (slot == null) return false;
        for (Class<?> type = slot.getClass(); type != null && type != Slot.class; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.equals(STATION_RESULT) || name.equals(STATION_RESULT_BASE)) return true;
        }
        return false;
    }

    /**
     * Whether the menu is Sophisticated Storage's storage menu, or a subclass of it. The crafting upgrade is looked for
     * when the craft starts: a menu without one (or with two) is UNSUPPORTED_MENU then.
     */
    public static boolean isStorageMenu(AbstractContainerMenu menu) {
        return named(menu, STORAGE_MENU);
    }

    /**
     * Whether the menu is Ars Nouveau's crafting lectern as the server crafts at it: the terminal menu's class name (or
     * a subclass's), its 46 slots, and the result and the nine cells active (the expanded view turns them off). Read by
     * name and slot count on both sides; the server checks the tile and the slots' containers as well.
     */
    public static boolean isLectern(AbstractContainerMenu menu) {
        if (!named(menu, LECTERN_MENU) || menu.slots.size() != LECTERN_SLOTS) return false;
        for (int i = 0; i <= LECTERN_CELLS; i++) {
            if (!menu.slots.get(i).isActive()) return false;
        }
        return true;
    }

    /** Whether the object's class, or one of its superclasses, has the given name. */
    private static boolean named(Object o, String name) {
        if (o == null) return false;
        for (Class<?> type = o.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (type.getName().equals(name)) return true;
        }
        return false;
    }

    /**
     * Calls a public no-argument method by name. Sophisticated's classes are only read through this, never compiled
     * against, so a changed name throws and the craft answers UNSUPPORTED_MENU or ERROR.
     */
    private static Object reflect(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /**
     * Calls a public method by name that takes the crafting player (Ars Nouveau's lectern keeps a grid and a result per
     * player). Read through reflection, the same as {@link #reflect}.
     */
    private static Object reflectPlayer(Object target, String name, Player player) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, Player.class);
        method.setAccessible(true);
        return method.invoke(target, player);
    }

    /**
     * The value of a field by name, on the object's class or one of its superclasses (Ars Nouveau's menu keeps its tile
     * in a protected field of its base class). Read through reflection, the same as {@link #reflect}.
     */
    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException notHere) {
                // not on this class: the superclass is looked at next
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Logs that the crafting upgrade's server path failed, once per server start. {@code cause} may be null. */
    private static void storageWarn(MinecraftServer server, String what, Throwable cause) {
        if (storageWarnedFor == server) return;
        storageWarnedFor = server;
        String text = "Server-side autocrafting at a Sophisticated crafting upgrade failed: {}";
        if (cause == null) NeiAddon.LOGGER.warn(text, what);
        else NeiAddon.LOGGER.warn(text, what, cause);
    }

    /** Logs that the lectern's server path failed, once per server start. {@code cause} may be null. */
    private static void lecternWarn(MinecraftServer server, String what, Throwable cause) {
        if (lecternWarnedFor == server) return;
        lecternWarnedFor = server;
        String text = "Server-side autocrafting at an Ars Nouveau crafting lectern failed: {}";
        if (cause == null) NeiAddon.LOGGER.warn(text, what);
        else NeiAddon.LOGGER.warn(text, what, cause);
    }

    /** Handles a one-recipe request on the server thread and answers it. Never throws. */
    public static void handle(ServerPlayer player, CraftRequest request) {
        List<ChainAnswer> answers = craftSteps(player, request.containerId(),
            List.of(new ChainStep(request.recipe(), request.times(), request.preferred())));
        ChainAnswer answer = answers.get(0);
        send(player, new CraftResult(request.requestId(), answer.crafted(), answer.reason(), answer.missing()));
    }

    /**
     * Handles a chain request on the server thread and answers it: the steps are crafted in order in this tick, and the
     * answer has one entry per step that ran (see {@link CraftChainResult}). A request over a limit is refused with
     * ERROR and nothing is crafted. Never throws.
     */
    public static void handleChain(ServerPlayer player, CraftChainRequest request) {
        List<ChainAnswer> answers = validChain(request.steps())
            ? craftSteps(player, request.containerId(), request.steps())
            : List.of(new ChainAnswer(0, Reason.ERROR, null));
        send(player, new CraftChainResult(request.requestId(), answers));
    }

    /** Whether a chain is within the limits of {@link Craft}. The decoder drops one over them; this checks again. */
    private static boolean validChain(List<ChainStep> steps) {
        if (steps.isEmpty() || steps.size() > Craft.MAX_STEPS) return false;
        long crafts = 0;
        for (ChainStep step : steps) {
            if (step.times() < 1 || step.times() > Craft.MAX_TIMES) return false;
            crafts += step.times();
        }
        return crafts <= Craft.MAX_CHAIN_CRAFTS;
    }

    /**
     * Crafts the steps in order on the server thread, one {@link Job} each, and returns the answers. It stops at the
     * first step that is not DONE, which is the last answer. When the server's time limit
     * ({@link ServerConfig#limitNanos}) is set and the steps so far have used it up, it stops after a DONE step
     * instead, so the answer is shorter than the request; at least one step always runs. The rate limit counts the
     * call once; BUSY and ERROR come alone. A step whose craft throws is answered ERROR with what it made, and the
     * chain stops there.
     */
    private static List<ChainAnswer> craftSteps(ServerPlayer player, int containerId, List<ChainStep> steps) {
        MinecraftServer server = player.getServer();
        if (server == null) return List.of(new ChainAnswer(0, Reason.ERROR, null));
        if (!allow(player, server)) return List.of(new ChainAnswer(0, Reason.BUSY, null));
        List<ChainAnswer> answers = new ArrayList<>(steps.size());
        AbstractContainerMenu syncMenu = null;
        long limitNanos = ServerConfig.limitNanos();
        long start = limitNanos > 0 ? System.nanoTime() : 0;
        for (ChainStep step : steps) {
            // never before the first step, never inside one
            if (limitNanos > 0 && !answers.isEmpty() && System.nanoTime() - start >= limitNanos) break;
            Job job = new Job(player, containerId, step);
            ChainAnswer answer;
            try {
                answer = job.run();
            } catch (RuntimeException | LinkageError e) {
                if (warnedFor != server) {
                    warnedFor = server;
                    NeiAddon.LOGGER.warn("Server-side autocrafting failed, the client falls back to its own clicks", e);
                }
                try {
                    if (job.tookResult) {
                        // The result may already be in the inventory and its take may have used up the ingredients.
                        // Putting the grid back would hand out the ingredients as well, so the grid stays as it is
                        // (nothing is deleted). A vanilla take counts; the station's only when its result arrived.
                        if (!job.station || job.stationGained() >= job.stationMade.getCount()) job.crafted++;
                        if (job.menu != null) job.menu.broadcastChanges();
                    } else if (!job.ae2 && !job.lectern) {
                        // the AE2 terminal and the lectern keep their grids as they are (the next fill clears them)
                        job.restoreGrid();
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // the grid stays as it is
                }
                answer = new ChainAnswer(job.crafted, Reason.ERROR, null);
            }
            if (job.ae2 || job.lectern) syncMenu = job.menu;
            answers.add(answer);
            if (answer.reason() != Reason.DONE) break;
        }
        // The AE2 and lectern paths send no sync of their own after a step: one sync of the menu at the end of the
        // request (the steps' own broadcast is for vanilla menus)
        if (syncMenu != null) syncMenu.broadcastChanges();
        return answers;
    }

    private static void send(ServerPlayer player, CustomPacketPayload message) {
        try {
            Platform.get().sendToPlayer(player, message);
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("Could not answer a server-side craft request", e);
        }
    }

    /** At most one request per player per tick and {@link #PER_SECOND} per second. */
    private static boolean allow(ServerPlayer player, MinecraftServer server) {
        long tick = server.getTickCount();
        if (LIMITS.size() > 256) LIMITS.values().removeIf(v -> v[0] < tick - 200 || v[0] > tick);
        long[] state = LIMITS.computeIfAbsent(player.getUUID(), id -> new long[] { -1, tick, 0 });
        // a tick counter that went back belongs to another server run
        if (tick < state[0] || tick < state[1] || tick - state[1] >= 20) {
            state[0] = -1;
            state[1] = tick;
            state[2] = 0;
        }
        if (state[0] == tick || state[2] >= PER_SECOND) return false;
        state[0] = tick;
        state[2]++;
        return true;
    }

    /** One step (one recipe's crafts) in progress. */
    private static final class Job {

        private final ServerPlayer player;
        private final int containerId;
        private final ChainStep step;
        AbstractContainerMenu menu;
        private List<Slot> grid = List.of();
        private List<Slot> inventory = List.of();
        private Slot resultSlot;
        /** Whether the open menu is Tinkers' Crafting Station (see {@link CraftServer#isStation}). */
        boolean station;
        int crafted;
        /** Set once the result is in the inventory, until the craft's onTake has finished. */
        boolean tookResult;
        /** The station's result as the take started (a copy), and the player's count of it before the take. */
        ItemStack stationMade = ItemStack.EMPTY;
        int stationBefore;
        /** Whether the open menu is an AE2 crafting terminal (see {@link Ae2CraftServer}), read by {@link #runAe2}. */
        boolean ae2;
        /** AE2: whether a craft was sent (a failure after that is an ERROR, not an unsupported menu). */
        private boolean ae2Clicked;
        /** AE2: the result of the craft in progress, and the player's count of it before the craft. */
        private ItemStack ae2Made = ItemStack.EMPTY;
        private int ae2Before;
        /** AE2: whether this step may put items into the network (set by runAe2 once its view can be refreshed). */
        private boolean ae2Network;
        /**
         * AE2: set after a network call threw. A throw ends the step with an answer that is not DONE, and the chain
         * stops there, so this is per request as well.
         */
        private boolean ae2NetworkFailed;
        /** AE2: how much of the craft in progress the network took (see {@link #stowCarried}). */
        private long ae2InNetwork;
        /** AE2: how much of the craft in progress stowCarried dropped at the player's feet (the craft still counts). */
        private int ae2Dropped;
        /** Whether the open menu is Sophisticated Storage's (see {@link CraftServer#isStorageMenu}), for runStorage. */
        boolean sophisticated;
        /** Sophisticated: the crafting upgrade's craft matrix (the cells are {@link #grid}) and the storage's slots. */
        private Container craftMatrix;
        /** Sophisticated: the cell the fill last wrote quietly this round, or -1. */
        private int lastWritten = -1;
        /** Sophisticated: the grid's item handler and its setStackInSlot, or null when they can't be found. */
        private Object quietHandler;
        private Method quietSetter;
        private boolean quietTried;
        private List<Slot> storageSlots = List.of();
        /** Sophisticated: whether a take is in progress, and the result of it with the count of it held before. */
        private boolean storageTaking;
        private ItemStack storageMade = ItemStack.EMPTY;
        private int storageBefore;
        /** Whether the open menu is Ars Nouveau's crafting lectern (see {@link CraftServer#isLectern}). */
        boolean lectern;
        /** Lectern: the tile the menu crafts for, its selected tab, and whether a take is running. */
        private Object lecternTile;
        private String lecternTab;
        private boolean lecternTaking;
        /** The asked-for recipe with its id, for the result container's setRecipeUsed and the lectern's check. */
        private RecipeHolder<?> holder;

        Job(ServerPlayer player, int containerId, ChainStep step) {
            this.player = player;
            this.containerId = containerId;
            this.step = step;
        }

        private ChainAnswer answer(Reason reason, ResourceLocation missing) {
            return new ChainAnswer(crafted, reason, missing);
        }

        ChainAnswer run() {
            MinecraftServer server = player.getServer();
            if (server == null) return answer(Reason.ERROR, null);
            menu = player.containerMenu;
            station = isStation(menu);
            ae2 = !station && Ae2CraftServer.isTerminal(menu);
            sophisticated = !station && !ae2 && isStorageMenu(menu);
            lectern = !station && !ae2 && !sophisticated && isLectern(menu);
            if (menu.containerId != containerId || player.isSpectator()
                || !player.isAlive() || player.isRemoved() || !menu.stillValid(player)
                || !station && !ae2 && !sophisticated && !lectern && menu.getClass() != CraftingMenu.class
                && menu.getClass() != InventoryMenu.class) {
                return answer(Reason.UNSUPPORTED_MENU, null);
            }
            Level level = player.level();
            RecipeHolder<?> found = server.getRecipeManager().byKey(step.recipe()).orElse(null);
            if (found == null || !(found.value() instanceof CraftingRecipe recipe)) {
                return answer(Reason.BAD_RECIPE, null);
            }
            holder = found;
            if (ae2) return runAe2(server, recipe, level);
            if (sophisticated) return runStorage(server, recipe, level);
            if (lectern) return runLectern(server, recipe, level);

            List<Slot> gridSlots = new ArrayList<>();
            List<Slot> inventorySlots = new ArrayList<>();
            CraftingContainer container = null;
            int width, height;
            if (station) {
                if (!readStation(gridSlots, inventorySlots)) return answer(Reason.UNSUPPORTED_MENU, null);
                width = STATION_SIDE;
                height = STATION_SIDE;
            } else {
                for (Slot slot : menu.slots) {
                    if (slot instanceof ResultSlot) {
                        if (resultSlot == null) resultSlot = slot;
                    } else if (slot.container instanceof CraftingContainer) {
                        gridSlots.add(slot);
                    } else if (slot.container instanceof Inventory && slot.getContainerSlot() < MAIN_SLOTS) {
                        inventorySlots.add(slot);
                    }
                }
                gridSlots.sort(Comparator.comparingInt(Slot::getContainerSlot));
                if (resultSlot == null || gridSlots.isEmpty()
                    || !(gridSlots.get(0).container instanceof CraftingContainer crafting)) {
                    return answer(Reason.UNSUPPORTED_MENU, null);
                }
                container = crafting;
                width = crafting.getWidth();
                height = crafting.getHeight();
            }
            if (gridSlots.size() != width * height || !recipe.canCraftInDimensions(width, height)) {
                return answer(Reason.BAD_RECIPE, null);
            }
            grid = gridSlots;
            inventory = inventorySlots;

            // lay the recipe out once: shaped at the grid's top-left, shapeless in the first slots
            Ingredient[] target = layout(recipe, width, height);
            if (target == null || countFilled(target) == 0) return answer(Reason.BAD_RECIPE, null);
            ItemStack expected = recipe.getResultItem(level.registryAccess());

            Set<Item> preferred = new HashSet<>();
            for (ResourceLocation id : step.preferred()) {
                BuiltInRegistries.ITEM.getOptional(id).ifPresent(preferred::add);
            }
            int times = Math.max(1, Math.min(step.times(), Craft.MAX_TIMES));

            // the grid starts empty: what is in it goes back into the inventory
            for (Slot slot : grid) {
                if (!slot.getItem().isEmpty() && !stash(slot)) return finish(Reason.INVENTORY_FULL, null, container);
            }
            Item[] previous = new Item[grid.size()];
            while (crafted < times) {
                for (int i = 0; i < grid.size(); i++) {
                    Slot slot = grid.get(i);
                    Ingredient ingredient = target[i];
                    ItemStack held = slot.getItem();
                    if (ingredient.isEmpty()) {
                        if (!held.isEmpty() && !stash(slot)) return finish(Reason.INVENTORY_FULL, null, container);
                        continue;
                    }
                    if (!held.isEmpty()) {
                        // a tool the last craft left in its slot stays; anything else (an empty bucket) goes back
                        if (ingredient.test(held)) {
                            previous[i] = held.getItem();
                            continue;
                        }
                        if (!stash(slot)) return finish(Reason.INVENTORY_FULL, null, container);
                    }
                    Slot from = pick(inventory, ingredient, preferred, previous[i]);
                    if (from == null) return finish(Reason.MISSING_INGREDIENT, missingId(ingredient), container);
                    ItemStack one = from.remove(1);
                    previous[i] = one.getItem();
                    slot.set(one);
                }
                // The result comes from the asked-for recipe itself. Mods such as FastWorkbench change when and how the
                // menu updates its result slot, and Polymorph can pick another recipe, so the slot isn't read.
                tookResult = false;
                // the station's grid is checked as a copy, so the recipe cannot change the real one
                CraftingContainer view = station ? new GridView(grid) : container;
                // A station disagreement on the first craft means this menu does not read like the recipe's (a mod's
                // own grid or result logic), so the client clicks instead of giving up the visit as a changed result.
                // Later crafts of the request keep RESULT_CHANGED: the grid has changed under the same recipe.
                Reason disagree = station && crafted == 0 ? Reason.UNSUPPORTED_MENU : Reason.RESULT_CHANGED;
                CraftingInput input = view.asCraftInput();
                if (!recipe.matches(input, level)) return finish(disagree, null, container);
                ItemStack shown = recipe.assemble(input, level.registryAccess());
                if (shown.isEmpty() || !expected.isEmpty()
                    && (!ItemStack.isSameItem(shown, expected) || shown.getCount() != expected.getCount())) {
                    return finish(Reason.RESULT_CHANGED, null, container);
                }
                ItemStack offered = ItemStack.EMPTY;
                if (station) {
                    // the station makes its own result from its grid: it must be the one the recipe makes, item tags
                    // and count included, since the count of the take is read with the tags
                    offered = resultSlot.getItem().copy();
                    if (offered.isEmpty() || !ItemStack.isSameItemSameComponents(offered, shown)
                        || offered.getCount() != shown.getCount()) {
                        return finish(disagree, null, container);
                    }
                } else {
                    // vanilla's own check: with doLimitedCrafting on, only recipes the player has unlocked
                    if (!(resultSlot.container instanceof ResultContainer results)
                        || !results.setRecipeUsed(level, player, holder)) {
                        return finish(Reason.RESULT_CHANGED, null, container);
                    }
                    setResult(shown);
                }
                if (!roomFor(shown)) return finish(Reason.INVENTORY_FULL, null, container);
                if (station) {
                    if (!takeAtStation(offered.copy())) return finish(Reason.ERROR, null, container);
                } else {
                    if (!take(level)) return finish(Reason.INVENTORY_FULL, null, container);
                    crafted++;
                }
            }
            return finish(Reason.DONE, null, container);
        }

        /**
         * The AE2 terminal's path (see {@link Ae2CraftServer}). The grid holds real items, so the fill is AE2's own
         * packet, and each craft is AE2's craft action: one craft, the result on the cursor, then into the player's
         * inventory and the network. The grid is read as a copy for the recipe's check, as the station's is. The grid
         * is left as AE2 left it: the next step's fill moves out what does not fit, and the client clears the grid at
         * the end of the run. The request's preferred items are not used, since AE2's fill picks its own items (the
         * network's best match, then the inventory). A failure is UNSUPPORTED_MENU before the first craft of the step
         * and ERROR after it.
         */
        private ChainAnswer runAe2(MinecraftServer server, CraftingRecipe recipe, Level level) {
            try {
                if (!recipe.canCraftInDimensions(STATION_SIDE, STATION_SIDE)) return answer(Reason.BAD_RECIPE, null);
                Ingredient[] target = layout(recipe, STATION_SIDE, STATION_SIDE);
                if (target == null || countFilled(target) == 0) return answer(Reason.BAD_RECIPE, null);
                List<Slot> gridSlots = new ArrayList<>(Ae2CraftServer.slots(menu, "CRAFTING_GRID"));
                List<Slot> results = Ae2CraftServer.slots(menu, "CRAFTING_RESULT");
                if (gridSlots.size() != STATION_SIDE * STATION_SIDE || results.size() != 1
                    || !Ae2CraftServer.isResult(results.get(0)) || !Ae2CraftServer.usable(menu)
                    || !menu.getCarried().isEmpty()) {
                    return answer(Reason.UNSUPPORTED_MENU, null);
                }
                Slot result = results.get(0);
                List<Slot> main = new ArrayList<>();
                for (Slot slot : menu.slots) {
                    if (slot.container instanceof Inventory && slot.getContainerSlot() < MAIN_SLOTS) main.add(slot);
                }
                inventory = main;
                // the grid's items and the results go into the network only when it has storage and its view can be
                // refreshed; without that, everything goes into the inventory as it did before the network was used
                ae2Network = Ae2CraftServer.networked(menu) && Ae2CraftServer.refreshNetworkView(menu);
                int times = Math.max(1, Math.min(step.times(), Craft.MAX_TIMES));
                while (crafted < times) {
                    ItemStack shown = showing(recipe, gridSlots, level);
                    if (!agrees(shown, result.getItem())) {
                        // the grid is not the recipe's yet: AE2 fills it (from the displaced items, the network, then
                        // the inventory), and the check is made again on what AE2 left. The grid's items go into the
                        // network first, or into the inventory without one. AE2's fill reads a cached copy of the
                        // network, so the view is refreshed right before the fill and sees those items.
                        if (ae2Network) gridToNetwork(gridSlots);
                        else gridToInventory(gridSlots);
                        // a wireless terminal can leave its access point's range mid-request: no refresh, no network
                        if (ae2Network && !Ae2CraftServer.refreshNetworkView(menu)) ae2Network = false;
                        Ae2CraftServer.fill(player, step.recipe());
                        shown = showing(recipe, gridSlots, level);
                        if (!agrees(shown, result.getItem())) return notShown(target, gridSlots);
                    }
                    // the result goes into the network, then the inventory; together they must have room for all of
                    // it (the network takes nothing unless the view could be refreshed)
                    if (!roomFor(shown) && freeFor(shown) + networkTakes(shown, true) < shown.getCount()) {
                        logAe2NoRoom(shown);
                        return answer(Reason.INVENTORY_FULL, null);
                    }
                    ae2Made = shown;
                    ae2Before = countInInventory(shown);
                    ae2InNetwork = 0;
                    ae2Dropped = 0;
                    Ae2CraftServer.craftOnce(result, player, () -> ae2Clicked = true);
                    stowCarried();
                    // what reached the inventory, the network or the ground all counts: the craft did happen
                    long landed = countInInventory(shown) - ae2Before + ae2InNetwork + ae2Dropped;
                    ae2Made = ItemStack.EMPTY;
                    if (landed != shown.getCount()) {
                        Ae2CraftServer.warnOnce(server, "the inventory and the network took " + landed + " of the "
                            + shown.getCount() + " made by a craft", null);
                        return answer(Reason.ERROR, null);
                    }
                    crafted++;
                }
                return answer(Reason.DONE, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                Ae2CraftServer.warnOnce(server, "the terminal's craft path threw", e);
                secureCarried();
                // a craft whose result reached the inventory, the network or the ground counts, as the station's does
                if (!ae2Made.isEmpty()) {
                    long landed = countInInventory(ae2Made) - ae2Before + ae2InNetwork + ae2Dropped;
                    if (landed == ae2Made.getCount()) crafted++;
                }
                ae2Made = ItemStack.EMPTY;
                return answer(crafted == 0 && !ae2Clicked ? Reason.UNSUPPORTED_MENU : Reason.ERROR, null);
            }
        }

        /**
         * Sophisticated Storage's crafting upgrade (see {@link CraftServer#isStorageMenu}). The grid is the upgrade's
         * own nine cells, and the storage is the menu's slots that are neither the player's, the cells nor the result.
         * Each craft fills the empty cells the way the station's fill does, from the player's inventory first and then
         * the storage, and checks the grid as a copy against the recipe and the result slot. The take is the menu's own
         * quickMoveStack of the result slot: one craft, with the remainders and the refill done by the menu. The grid
         * goes back at the end of the step, into the inventory first and then the storage. A failure before the first
         * craft is UNSUPPORTED_MENU, a later one ERROR.
         */
        private ChainAnswer runStorage(MinecraftServer server, CraftingRecipe recipe, Level level) {
            try {
                if (!menu.getCarried().isEmpty() || !readStorage()) return answer(Reason.UNSUPPORTED_MENU, null);
                if (!recipe.canCraftInDimensions(STATION_SIDE, STATION_SIDE)) return answer(Reason.BAD_RECIPE, null);
                Ingredient[] target = layout(recipe, STATION_SIDE, STATION_SIDE);
                if (target == null || countFilled(target) == 0) return answer(Reason.BAD_RECIPE, null);
                ItemStack expected = recipe.getResultItem(level.registryAccess());

                Set<Item> preferred = new HashSet<>();
                for (ResourceLocation id : step.preferred()) {
                    BuiltInRegistries.ITEM.getOptional(id).ifPresent(preferred::add);
                }
                int times = Math.max(1, Math.min(step.times(), Craft.MAX_TIMES));

                // the inventory first, then the storage: the cells are filled from these, and returned items go to them
                List<Slot> places = new ArrayList<>(inventory);
                places.addAll(storageSlots);
                List<Slot> sources = new ArrayList<>();
                for (Slot slot : places) {
                    if (slot.mayPickup(player)) sources.add(slot);
                }

                // the grid starts empty: what is in it goes back into the inventory, as the vanilla fill does
                for (int i = 0; i < grid.size(); i++) {
                    if (!grid.get(i).getItem().isEmpty() && !cellBack(i, places)) {
                        return finish(Reason.INVENTORY_FULL, null, null);
                    }
                }
                Item[] previous = new Item[grid.size()];
                while (crafted < times) {
                    for (int i = 0; i < grid.size(); i++) {
                        Ingredient ingredient = target[i];
                        ItemStack held = grid.get(i).getItem();
                        if (ingredient.isEmpty()) {
                            if (!held.isEmpty() && !cellBack(i, places)) {
                                return finish(Reason.RESULT_CHANGED, null, null);
                            }
                            continue;
                        }
                        if (!held.isEmpty()) {
                            // a tool the last craft left in its cell stays; anything else that does not fit goes back
                            if (ingredient.test(held)) {
                                previous[i] = held.getItem();
                                continue;
                            }
                            if (!cellBack(i, places)) return finish(Reason.RESULT_CHANGED, null, null);
                        }
                        Slot from = pick(sources, ingredient, preferred, previous[i]);
                        if (from == null) return finish(Reason.MISSING_INGREDIENT, missingId(ingredient), null);
                        ItemStack one = from.remove(1);
                        if (one.isEmpty()) return finish(Reason.MISSING_INGREDIENT, missingId(ingredient), null);
                        previous[i] = one.getItem();
                        try {
                            quietSet(i, one);
                            lastWritten = i;
                        } catch (RuntimeException | LinkageError e) {
                            // the rule for throwing writes (see setCell): a stack the cell holds is not given back
                            if (!sameStack(craftMatrix.getItem(i), one)) giveBack(from, one, places);
                            throw e;
                        }
                    }
                    // one ordinary write after the quiet ones: the upgrade looks the recipe up once for the whole fill.
                    // Its list of the grid's items is reset first, since setItem resets it only after the lookup.
                    if (lastWritten >= 0) {
                        resetGridList();
                        craftMatrix.setItem(lastWritten, craftMatrix.getItem(lastWritten));
                    }
                    lastWritten = -1;
                    // the grid is read as a copy, so the recipe cannot change the menu's cells
                    GridView view = new GridView(grid);
                    Reason disagree = crafted == 0 ? Reason.UNSUPPORTED_MENU : Reason.RESULT_CHANGED;
                    CraftingInput input = view.asCraftInput();
                    if (!recipe.matches(input, level)) return finish(disagree, null, null);
                    ItemStack shown = recipe.assemble(input, level.registryAccess());
                    if (shown.isEmpty() || !expected.isEmpty()
                        && (!ItemStack.isSameItem(shown, expected) || shown.getCount() != expected.getCount())) {
                        return finish(Reason.RESULT_CHANGED, null, null);
                    }
                    // the menu's result must be this recipe's (item, tags and count), or the take crafts another one
                    if (!agrees(shown, resultSlot.getItem())) return finish(disagree, null, null);
                    if (!roomFor(shown)) return finish(Reason.INVENTORY_FULL, null, null);

                    storageMade = shown;
                    storageBefore = countHeld(shown);
                    storageTaking = true;
                    ItemStack made = menu.quickMoveStack(player, resultSlot.index);
                    storageTaking = false;
                    // nothing had room for the result: nothing was consumed
                    if (made.isEmpty()) return finish(Reason.INVENTORY_FULL, null, null);
                    if (!ItemStack.isSameItemSameComponents(made, shown) || made.getCount() != shown.getCount()) {
                        storageWarn(server, "the take gave " + made.getCount() + " of the " + shown.getCount()
                            + " made by a craft", null);
                        return finish(Reason.ERROR, null, null);
                    }
                    crafted++;
                }
                return finish(Reason.DONE, null, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                storageWarn(server, "the crafting upgrade's craft path threw", e);
                boolean took = storageTaking;
                storageTaking = false;
                try {
                    if (took) {
                        // a take that threw may have moved the result already: it counts when the inventory and the
                        // storage gained exactly its count. The grid stays as it is: its ingredients may be used up.
                        boolean gained = !storageMade.isEmpty()
                            && countHeld(storageMade) - storageBefore == storageMade.getCount();
                        if (gained) crafted++;
                    } else {
                        restoreGrid();
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // the grid stays as it is
                }
                return answer(crafted == 0 && !took ? Reason.UNSUPPORTED_MENU : Reason.ERROR, null);
            }
        }

        /**
         * Ars Nouveau's crafting lectern (see {@link CraftServer#isLectern}). The grid and the result belong to the
         * lectern's tile, and its storage is not among the menu's slots. A step's fill is the lectern's own transfer
         * (what its JEI transfer calls): it moves the grid into the storage, then fills each cell from the storage and
         * the inventory. Each craft checks the grid as a copy against the recipe and the result slot, then takes with
         * the vanilla take. The result slot's onTake makes the lectern craft (it uses up the ingredients, puts the
         * remainders back and works out the next result), but only while the lectern's current recipe is this one, so
         * that is checked first. The lectern's fill picks its items by its own storage counts, so the run's preferred
         * items are not guaranteed. That fill drops at the lectern what its storage can't take, so the grid goes to the
         * player before each fill (the step stops if anything stays) and again when the step ends. What fits nowhere
         * stays in the grid: a shift-click on a cell pushes into the storage and keeps the rest in the cell. A failure
         * before the first take is UNSUPPORTED_MENU, a later one ERROR.
         */
        private ChainAnswer runLectern(MinecraftServer server, CraftingRecipe recipe, Level level) {
            boolean read = false;
            try {
                if (!menu.getCarried().isEmpty() || !readLectern()) return answer(Reason.UNSUPPORTED_MENU, null);
                read = true;
                if (!recipe.canCraftInDimensions(STATION_SIDE, STATION_SIDE)) return answer(Reason.BAD_RECIPE, null);
                Ingredient[] target = layout(recipe, STATION_SIDE, STATION_SIDE);
                if (target == null || countFilled(target) == 0) return answer(Reason.BAD_RECIPE, null);
                ItemStack expected = recipe.getResultItem(level.registryAccess());

                Set<Item> preferred = new HashSet<>();
                for (ResourceLocation id : step.preferred()) {
                    BuiltInRegistries.ITEM.getOptional(id).ifPresent(preferred::add);
                }
                int times = Math.max(1, Math.min(step.times(), Craft.MAX_TIMES));
                ItemStack[][] candidates = candidates(target, preferred);

                while (crafted < times) {
                    ItemStack shown = showing(recipe, grid, level);
                    if (!agrees(shown, resultSlot.getItem())) {
                        // the grid is not the recipe's yet: the lectern fills it, then the check is made again on what
                        // it left. Its fill first clears the grid into its storage and drops what doesn't fit at the
                        // lectern (all of it when no storage is linked), so the grid goes to the player first, and
                        // the step stops if anything is left in it.
                        gridToInventory(grid);
                        for (Slot cell : grid) {
                            if (!cell.getItem().isEmpty()) return answer(Reason.INVENTORY_FULL, null);
                        }
                        fillLectern(candidates);
                        shown = showing(recipe, grid, level);
                        if (!agrees(shown, resultSlot.getItem())) return notShown(target, grid);
                    }
                    if (!expected.isEmpty()
                        && (!ItemStack.isSameItem(shown, expected) || shown.getCount() != expected.getCount())) {
                        return answer(Reason.RESULT_CHANGED, null);
                    }
                    // vanilla's own check: with doLimitedCrafting on, only recipes the player has unlocked
                    if (!(resultSlot.container instanceof ResultContainer results)
                        || !results.setRecipeUsed(level, player, holder)) {
                        return answer(Reason.RESULT_CHANGED, null);
                    }
                    setResult(shown);
                    if (!roomFor(shown)) return answer(Reason.INVENTORY_FULL, null);
                    // the lectern's craft does nothing without its current recipe, which would hand out the result for
                    // free
                    if (!lecternCurrent(recipe)) {
                        return answer(crafted == 0 ? Reason.UNSUPPORTED_MENU : Reason.RESULT_CHANGED, null);
                    }
                    lecternTaking = true;
                    boolean moved = take(level);
                    lecternTaking = false;
                    if (!moved) return answer(Reason.INVENTORY_FULL, null);
                    crafted++;
                }
                return answer(Reason.DONE, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                lecternWarn(server, "the lectern's craft path threw", e);
                boolean took = lecternTaking;
                lecternTaking = false;
                // a take whose result reached the inventory counts, as the vanilla take's does
                if (tookResult) {
                    crafted++;
                    tookResult = false;
                }
                return answer(crafted == 0 && !took ? Reason.UNSUPPORTED_MENU : Reason.ERROR, null);
            } finally {
                // what the step leaves in the grid goes to the player: the lectern pushes the grid into its storage
                // (and drops what doesn't fit) when the client's cleanup shift-clicks it or the next fill clears it
                if (read) {
                    try {
                        gridToInventory(grid);
                    } catch (RuntimeException | LinkageError e) {
                        lecternWarn(server, "moving the lectern's grid to the player threw", e);
                    }
                }
            }
        }

        /**
         * Finds the lectern's tile (the menu's {@code te}) and checks the menu's slots against it: the result (slot 0)
         * on the tile's result container, the nine cells (1-9) on the tile's crafting container in order (the tile
         * keeps one of each per player), and the player's 36 slots after them. The menu must be the lectern as
         * {@link CraftServer#isLectern} reads it (the expanded view fails that). Sets the grid, the result, the
         * inventory, the tile and the selected tab. False when any of that does not hold.
         */
        private boolean readLectern() throws ReflectiveOperationException {
            Object tile = field(menu, "te");
            if (!named(tile, LECTERN_TILE) || !isLectern(menu)) return false;
            // the lectern keeps a grid and a result per player
            if (!(reflectPlayer(tile, "getCraftingInv", player) instanceof CraftingContainer crafting)
                || crafting.getWidth() != STATION_SIDE || crafting.getHeight() != STATION_SIDE) {
                return false;
            }
            if (!(reflectPlayer(tile, "getCraftResult", player) instanceof ResultContainer results)) return false;
            Slot result = menu.slots.get(0);
            if (!(result instanceof ResultSlot) || result.container != results) return false;
            List<Slot> cells = new ArrayList<>(LECTERN_CELLS);
            for (int i = 0; i < LECTERN_CELLS; i++) {
                Slot slot = menu.slots.get(i + 1);
                if (slot.container != crafting || slot.getContainerSlot() != i) return false;
                cells.add(slot);
            }
            List<Slot> main = new ArrayList<>(MAIN_SLOTS);
            boolean[] seen = new boolean[MAIN_SLOTS];
            for (int i = LECTERN_CELLS + 1; i < LECTERN_SLOTS; i++) {
                Slot slot = menu.slots.get(i);
                int at = slot.getContainerSlot();
                if (slot.container != player.getInventory() || at < 0 || at >= MAIN_SLOTS || seen[at]) return false;
                seen[at] = true;
                main.add(slot);
            }
            // the menu keeps each player's tab in a map; null until the player picks one: the lectern reads it as
            // "all tabs"
            if (!(field(menu, "tabs") instanceof Map<?, ?> tabs)) return false;
            Object tab = tabs.get(player.getUUID());
            if (tab != null && !(tab instanceof String)) return false;
            grid = cells;
            resultSlot = result;
            inventory = main;
            lecternTile = tile;
            lecternTab = (String) tab;
            return true;
        }

        /**
         * The lectern's fill candidates: for each cell, the copies of its ingredient's items with the chosen ones
         * first, and none for an empty cell. The lectern tries them in the order of its own storage counts, so the
         * chosen first is only a preference.
         */
        private static ItemStack[][] candidates(Ingredient[] target, Set<Item> preferred) {
            ItemStack[][] cells = new ItemStack[target.length][];
            for (int i = 0; i < target.length; i++) {
                List<ItemStack> chosen = new ArrayList<>();
                List<ItemStack> others = new ArrayList<>();
                if (!target[i].isEmpty()) {
                    for (ItemStack stack : target[i].getItems()) {
                        if (stack.isEmpty()) continue;
                        (preferred.contains(stack.getItem()) ? chosen : others).add(stack.copy());
                    }
                }
                chosen.addAll(others);
                cells[i] = chosen.toArray(new ItemStack[0]);
            }
            return cells;
        }

        /** The lectern's own fill (its JEI transfer's call): the grid goes to its storage, then each cell takes one. */
        private void fillLectern(ItemStack[][] candidates) throws ReflectiveOperationException {
            Method transfer = lecternTile.getClass()
                .getMethod("transferToGrid", Player.class, ItemStack[][].class, String.class);
            transfer.setAccessible(true);
            transfer.invoke(lecternTile, player, candidates, lecternTab);
        }

        /** Whether the lectern's current recipe (a private field of its tile) is the requested one; false if none. */
        private boolean lecternCurrent(CraftingRecipe recipe) throws ReflectiveOperationException {
            Object current = field(lecternTile, "currentRecipe");
            // A recipe has no id of its own here. The lectern keeps the recipe manager's own object (the value of the
            // holder its lookup found), the same object this request's holder has, so the same object is the same
            // recipe. After a reload the two differ until the lectern looks again, which stops the step.
            return current != null && current == recipe;
        }

        /**
         * Finds the crafting upgrade and the storage of the open menu, by name and reflection. The menu must have
         * exactly one crafting upgrade whose ten slots are the menu's own (the nine cells, then the result), and as
         * many storage slots as the menu reports. Sets the grid, the result, the craft matrix, the inventory and the
         * storage. False when any of that does not hold.
         */
        private boolean readStorage() throws ReflectiveOperationException {
            if (!(reflect(menu, "getUpgradeContainers") instanceof Map<?, ?> containers)) return false;
            Object upgrade = null;
            int upgrades = 0;
            for (Object part : containers.values()) {
                if (named(part, STORAGE_CRAFTING)) {
                    upgrade = part;
                    upgrades++;
                }
            }
            if (upgrades != 1) return false;
            if (!(reflect(upgrade, "getSlots") instanceof List<?> all) || all.size() != STORAGE_SLOTS) return false;
            if (!(reflect(upgrade, "getRecipeSlots") instanceof List<?> cells)
                || !cells.equals(all.subList(0, STORAGE_CELLS))) {
                return false;
            }
            List<Slot> slots = new ArrayList<>(STORAGE_SLOTS);
            for (Object o : all) {
                if (!(o instanceof Slot slot)) return false;
                // the menu hands out each of these at its index, so they are the menu's own slots
                if (menu.getSlot(slot.index) != slot) return false;
                slots.add(slot);
            }
            if (!(slots.get(STORAGE_SLOTS - 1) instanceof ResultSlot result)) return false;
            if (!(reflect(upgrade, "getCraftMatrix") instanceof Container matrix)
                || matrix.getContainerSize() != STORAGE_CELLS) {
                return false;
            }
            // The cell slots cannot be compared by container, since they sit on Forge's shared empty container. Each
            // cell must hold the matrix's own stack instead: both read one item handler, so both return one object.
            for (int i = 0; i < STORAGE_CELLS; i++) {
                ItemStack cell = slots.get(i).getItem();
                ItemStack written = matrix.getItem(i);
                boolean same = cell == written || cell.isEmpty() && written.isEmpty();
                if (slots.get(i).getContainerSlot() != i || !same) return false;
            }
            List<Slot> cellSlots = slots.subList(0, STORAGE_CELLS);
            List<Slot> storage = new ArrayList<>();
            for (Slot slot : menu.slots) {
                if (!(slot.container instanceof Inventory) && slot != result && !cellSlots.contains(slot)) {
                    storage.add(slot);
                }
            }
            if (!(reflect(menu, "getNumberOfStorageInventorySlots") instanceof Integer size)
                || storage.size() != size) {
                return false;
            }
            List<Slot> main = new ArrayList<>();
            for (Slot slot : menu.slots) {
                if (slot.container instanceof Inventory && slot.getContainerSlot() < MAIN_SLOTS) main.add(slot);
            }
            grid = new ArrayList<>(cellSlots);
            resultSlot = result;
            craftMatrix = matrix;
            inventory = main;
            storageSlots = storage;
            return true;
        }

        /** How many of the item (with its tags) the player's inventory and the storage hold. */
        private int countHeld(ItemStack like) {
            return countIn(inventory, like) + countIn(storageSlots, like);
        }

        /** How many of the item (with its tags) the slots hold. */
        private static int countIn(List<Slot> slots, ItemStack like) {
            int count = 0;
            for (Slot slot : slots) {
                ItemStack held = slot.getItem();
                if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, like)) count += held.getCount();
            }
            return count;
        }

        /**
         * Moves a cell's stack into the places, in their order (see {@link #putInto}). Each move reduces the cell
         * first, then writes the place (see {@link #moveFromCell}), so the cell keeps what has not moved. True when the
         * cell is empty.
         */
        private boolean cellBack(int cell, List<Slot> places) {
            ItemStack held = grid.get(cell).getItem().copy();
            if (held.isEmpty()) return true;
            if (held.isStackable()) {
                for (Slot slot : places) {
                    if (held.isEmpty()) break;
                    ItemStack there = slot.getItem();
                    if (there.isEmpty() || !ItemStack.isSameItemSameComponents(held, there) || !slot.mayPlace(held)) {
                        continue;
                    }
                    int room = slot.getMaxStackSize(there) - there.getCount();
                    if (room <= 0) continue;
                    int moved = Math.min(room, held.getCount());
                    ItemStack after = there.copy();
                    after.grow(moved);
                    held = moveFromCell(cell, held, moved, slot, after);
                }
            }
            for (Slot slot : places) {
                if (held.isEmpty()) break;
                if (!slot.getItem().isEmpty() || !slot.mayPlace(held)) continue;
                int moved = Math.min(held.getCount(), slot.getMaxStackSize(held));
                ItemStack part = held.copy();
                part.setCount(moved);
                held = moveFromCell(cell, held, moved, slot, part);
            }
            return held.isEmpty();
        }

        /**
         * Moves {@code moved} of the cell's stack {@code held} into the slot, as {@code placed} (what the slot holds
         * afterwards). The cell is reduced first; if the place write throws without storing, the cell gets {@code held}
         * back. Returns what the cell keeps.
         */
        private ItemStack moveFromCell(int cell, ItemStack held, int moved, Slot slot, ItemStack placed) {
            ItemStack rest = held.copy();
            rest.shrink(moved);
            setCell(cell, rest);
            try {
                slot.set(placed);
            } catch (RuntimeException | LinkageError e) {
                if (!sameStack(slot.getItem(), placed)) {
                    setCell(cell, held);
                    throw e;
                }
            }
            return rest;
        }

        /**
         * Sophisticated: writes a cell without the upgrade's recipe lookup. The craft matrix's setItem stores into its
         * item handler, then looks the recipe up again (slow in a big pack, and the fill writes up to nine cells per
         * craft). This stores into the same handler only (its getItem reads that handler), and the fill ends with one
         * ordinary setItem for the lookup. When the handler can't be found, it is the ordinary setItem.
         */
        private void quietSet(int cell, ItemStack stack) {
            if (!quietTried) {
                quietTried = true;
                try {
                    if (field(craftMatrix, "supplyInventory") instanceof Supplier<?> supplier) {
                        Object handler = supplier.get();
                        quietSetter = handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class);
                        quietHandler = handler;
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    quietSetter = null;
                }
            }
            if (quietSetter == null) {
                craftMatrix.setItem(cell, stack);
                return;
            }
            try {
                quietSetter.invoke(quietHandler, cell, stack);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("the grid's handler refused a write", e);
            }
        }

        /**
         * Sophisticated: marks the craft matrix's list of its items as out of date (its private itemsInitialized
         * flag), so the recipe lookup after the quiet writes reads the cells as they are now. Nothing when the flag
         * can't be found: the list is then only used by recipes that read it, and a stale one fails the result check.
         */
        private void resetGridList() {
            if (quietSetter == null) return;
            for (Class<?> type = craftMatrix.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field flag = type.getDeclaredField("itemsInitialized");
                    flag.setAccessible(true);
                    flag.setBoolean(craftMatrix, false);
                    return;
                } catch (NoSuchFieldException notHere) {
                    // not on this class: the superclass is looked at next
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    return;
                }
            }
        }

        /**
         * Writes a cell. The rule for a write that throws: the matrix may have stored the stack before it threw (its
         * recompute of the recipe runs after the store), so the cell is read back. A store that happened counts and the
         * error is logged at debug; otherwise nothing changed and the error goes on.
         */
        private void setCell(int cell, ItemStack wanted) {
            try {
                craftMatrix.setItem(cell, wanted);
            } catch (RuntimeException | LinkageError e) {
                if (!sameStack(craftMatrix.getItem(cell), wanted)) throw e;
                NeiAddon.LOGGER.debug("The craft matrix threw after storing a stack in cell {}", cell, e);
            }
        }

        /** Whether two stacks are the same: both empty, or the same item and tags with the same count. */
        private static boolean sameStack(ItemStack a, ItemStack b) {
            if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
            return ItemStack.isSameItemSameComponents(a, b) && a.getCount() == b.getCount();
        }

        /**
         * Puts back a stack that came out of a source slot but could not reach the grid: into that slot if it is still
         * empty and takes it, else into the places, else on the ground. Nothing is dropped while a place takes it.
         */
        private void giveBack(Slot from, ItemStack stack, List<Slot> places) {
            if (from.getItem().isEmpty() && from.mayPlace(stack)) {
                from.set(stack);
                return;
            }
            putInto(places, stack);
            if (!stack.isEmpty()) player.drop(stack, false);
        }

        /**
         * Moves what fits of the stack into the slots, in their order: first onto stacks of the same item, then into
         * empty slots. The stack shrinks by what moved. Slots are written with set, never by changing a stack in place,
         * since a storage slot may hand out a copy of its stack.
         */
        private void putInto(List<Slot> slots, ItemStack stack) {
            if (stack.isStackable()) {
                for (Slot slot : slots) {
                    if (stack.isEmpty()) return;
                    ItemStack held = slot.getItem();
                    if (held.isEmpty() || !ItemStack.isSameItemSameComponents(stack, held) || !slot.mayPlace(stack)) {
                        continue;
                    }
                    int room = slot.getMaxStackSize(held) - held.getCount();
                    if (room <= 0) continue;
                    int moved = Math.min(room, stack.getCount());
                    ItemStack after = held.copy();
                    after.grow(moved);
                    slot.set(after);
                    stack.shrink(moved);
                }
            }
            for (Slot slot : slots) {
                if (stack.isEmpty()) return;
                if (!slot.getItem().isEmpty() || !slot.mayPlace(stack)) continue;
                int moved = Math.min(stack.getCount(), slot.getMaxStackSize(stack));
                slot.set(stack.split(moved));
            }
        }

        /**
         * Lays the recipe out on a width x height grid: shaped at the grid's top-left, shapeless in the first cells.
         * Null when it does not fit the grid.
         */
        private static Ingredient[] layout(CraftingRecipe recipe, int width, int height) {
            Ingredient[] target = new Ingredient[width * height];
            java.util.Arrays.fill(target, Ingredient.EMPTY);
            List<Ingredient> ingredients = recipe.getIngredients();
            if (recipe instanceof ShapedRecipe shaped) {
                for (int i = 0; i < ingredients.size(); i++) {
                    int row = i / shaped.getWidth(), column = i % shaped.getWidth();
                    if (row >= height || column >= width) return null;
                    target[row * width + column] = ingredients.get(i);
                }
            } else {
                int filled = 0;
                for (Ingredient ingredient : ingredients) {
                    if (ingredient.isEmpty()) continue;
                    if (filled >= target.length) return null;
                    target[filled++] = ingredient;
                }
            }
            return target;
        }

        /** How many cells of a laid-out recipe hold an ingredient. */
        private static int countFilled(Ingredient[] target) {
            int filled = 0;
            for (Ingredient ingredient : target) {
                if (!ingredient.isEmpty()) filled++;
            }
            return filled;
        }

        /** What the recipe makes of the grid as it is (read through a copy), or EMPTY when it does not match. */
        private static ItemStack showing(CraftingRecipe recipe, List<Slot> gridSlots, Level level) {
            CraftingInput input = new GridView(gridSlots).asCraftInput();
            return recipe.matches(input, level) ? recipe.assemble(input, level.registryAccess()) : ItemStack.EMPTY;
        }

        /** Whether the result slot shows what the grid makes: the same item, tags and count. */
        private static boolean agrees(ItemStack shown, ItemStack held) {
            return !shown.isEmpty() && ItemStack.isSameItemSameComponents(shown, held)
                && shown.getCount() == held.getCount();
        }

        /**
         * AE2: the grid does not make the recipe's result, even after AE2's fill. An empty cell the recipe needs names
         * the missing ingredient; with every needed cell filled, the grid makes another result (or none): a changed
         * result.
         */
        private ChainAnswer notShown(Ingredient[] target, List<Slot> gridSlots) {
            for (int i = 0; i < target.length; i++) {
                if (!target[i].isEmpty() && gridSlots.get(i).getItem().isEmpty()) {
                    return answer(Reason.MISSING_INGREDIENT, missingId(target[i]));
                }
            }
            return answer(Reason.RESULT_CHANGED, null);
        }

        /**
         * AE2 and the lectern: moves each grid stack that fits whole into the player's main slots; one that doesn't fit
         * whole stays in its cell. The cell is emptied first and the stack then inserted, so a throw can't leave it in
         * both; if the insert throws, what it had not placed yet goes back into the cell (or is dropped).
         */
        private void gridToInventory(List<Slot> gridSlots) {
            for (Slot cell : gridSlots) {
                ItemStack held = cell.getItem();
                if (held.isEmpty() || !roomFor(held)) continue;
                ItemStack moving = held.copy();
                cell.set(ItemStack.EMPTY);
                try {
                    insert(moving, false);
                } catch (RuntimeException | LinkageError e) {
                    if (!moving.isEmpty()) {
                        if (cell.getItem().isEmpty()) cell.set(moving);
                        else player.drop(moving, false);
                    }
                    throw e;
                }
                if (!moving.isEmpty()) player.drop(moving, false);
            }
        }

        /**
         * AE2: moves each grid stack into the network as far as it goes, then into the player's main slots. What fits
         * nowhere stays in its cell. The cell is emptied before the stack moves, so a throw can't leave the stack in
         * both; if a move throws, what has not moved yet goes back into the cell (or is dropped when the cell got
         * something). The caller refreshes the network's view before the fill, so the fill sees what was put in.
         */
        private void gridToNetwork(List<Slot> gridSlots) throws ReflectiveOperationException {
            for (Slot cell : gridSlots) {
                ItemStack held = cell.getItem();
                if (held.isEmpty()) continue;
                ItemStack moving = held.copy();
                cell.set(ItemStack.EMPTY);
                try {
                    intoNetwork(moving);
                    insert(moving, false);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    if (!moving.isEmpty()) {
                        if (cell.getItem().isEmpty()) cell.set(moving);
                        else player.drop(moving, false);
                    }
                    throw e;
                }
                if (!moving.isEmpty()) cell.set(moving);
            }
        }

        /**
         * AE2: logs why a result found no room, so a report shows whether the network was out of space or not used at
         * all. It moves no items; it does refresh the network's view to see whether that works, which is harmless here
         * since the step ends. A failure to read is logged instead.
         */
        private void logAe2NoRoom(ItemStack shown) {
            try {
                boolean storage = Ae2CraftServer.networked(menu);
                boolean view = storage && Ae2CraftServer.refreshNetworkView(menu);
                long takes = storage ? Ae2CraftServer.toNetwork(menu, shown, true) : 0;
                NeiAddon.LOGGER.info("Autocrafting on the server: no room for {}x {} at the AE2 terminal (network "
                    + "storage: {}, view refreshable: {}, network used this step: {}, turned off by an error: {}, the "
                    + "network would take {}{})", shown.getCount(), BuiltInRegistries.ITEM.getKey(shown.getItem()),
                    storage, view, ae2Network, ae2NetworkFailed, takes,
                    storage && takes < shown.getCount() ? "; " + Ae2CraftServer.refusal(menu, shown) : "");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                NeiAddon.LOGGER.info("Autocrafting on the server: no room at the AE2 terminal, and its network could "
                    + "not be read: {}", e.toString());
            }
        }

        /** AE2: whether the network may take items now: the runAe2 gate, and no network call has thrown yet. */
        private boolean networkUsable() {
            return ae2Network && !ae2NetworkFailed;
        }

        /**
         * AE2: how much of the stack the network takes (simulated or not), or 0 when it can't be used. A throw turns
         * the network off for the rest of the request.
         */
        private long networkTakes(ItemStack stack, boolean simulate) throws ReflectiveOperationException {
            if (!networkUsable()) return 0;
            try {
                return Ae2CraftServer.toNetwork(menu, stack, simulate);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                ae2NetworkFailed = true;
                throw e;
            }
        }

        /**
         * AE2: puts the stack into the network as far as the network takes it, and takes that off the stack. Returns
         * what the network took. A throw turns the network off for the rest of the request. If the insert throws, the
         * stack is read back: what the network holds now, against what it held before, comes off the stack. The
         * read-back assumes nothing else moved this item in the network between the two reads; the server thread runs
         * one request at a time, so nothing does. If the read-back throws too, nothing is known to have landed, so the
         * stack stays whole. The only residual risk is a partial insert followed by a throw in both the insert and the
         * read-back: that part is then in the network and on the stack, a possible duplicate, never a loss.
         */
        private long intoNetwork(ItemStack stack) throws ReflectiveOperationException {
            if (!networkUsable()) return 0;
            int count = stack.getCount();
            long before;
            try {
                before = Ae2CraftServer.stored(menu, stack);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // nothing has moved yet
                ae2NetworkFailed = true;
                throw e;
            }
            long took;
            try {
                took = Ae2CraftServer.toNetwork(menu, stack, false);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                ae2NetworkFailed = true;
                try {
                    long landed = Ae2CraftServer.stored(menu, stack) - before;
                    stack.shrink((int) Math.max(0, Math.min(count, landed)));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError readBackFailed) {
                    // nothing is known to have landed, so nothing comes off the stack (see the comment above)
                }
                throw e;
            }
            int accepted = (int) Math.max(0, Math.min(count, took));
            stack.shrink(accepted);
            return accepted;
        }

        /**
         * AE2: moves the cursor's stack into the network, then into the inventory, and drops what neither takes. The
         * stack leaves the cursor only as it lands somewhere, so if this throws, whatever has not moved yet is still on
         * the cursor. What the network took is added to {@link #ae2InNetwork}, and what is dropped to
         * {@link #ae2Dropped}.
         */
        private void stowCarried() throws ReflectiveOperationException {
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) return;
            // the network first, so long chains don't fill the inventory; intoNetwork does nothing when the network
            // can't be used, and what it leaves goes into the inventory
            int had = carried.getCount();
            try {
                intoNetwork(carried);
            } finally {
                ae2InNetwork += had - carried.getCount();
            }
            if (!carried.isEmpty()) insert(carried, false);
            if (carried.isEmpty()) {
                menu.setCarried(ItemStack.EMPTY);
                return;
            }
            int rest = carried.getCount();
            player.drop(carried.copy(), false);
            ae2Dropped += rest;
            menu.setCarried(ItemStack.EMPTY);
        }

        /** AE2 after a failure: {@link #stowCarried}, so the cursor isn't left holding the result. */
        private void secureCarried() {
            try {
                stowCarried();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // what didn't move is still on the cursor, which the client sees and the menu returns on close
            }
        }

        /**
         * Reads the station's slots: 0-8 the 3x3 grid (one container that is not the player's, row-major), 9 the
         * result, 10-45 the player's 36 slots. Anything else, such as a side inventory, makes the station unsupported.
         */
        private boolean readStation(List<Slot> gridSlots, List<Slot> inventorySlots) {
            if (menu.slots.size() != STATION_SLOTS) return false;
            Container shared = menu.slots.get(0).container;
            if (shared instanceof Inventory) return false;
            for (int i = 0; i < STATION_SIDE * STATION_SIDE; i++) {
                Slot slot = menu.slots.get(i);
                if (slot.container != shared || slot.getContainerSlot() != i) return false;
                gridSlots.add(slot);
            }
            resultSlot = menu.slots.get(STATION_RESULT_INDEX);
            boolean[] seen = new boolean[MAIN_SLOTS];
            for (int i = STATION_RESULT_INDEX + 1; i < STATION_SLOTS; i++) {
                Slot slot = menu.slots.get(i);
                int at = slot.getContainerSlot();
                if (slot.container != player.getInventory() || at < 0 || at >= MAIN_SLOTS || seen[at]) return false;
                seen[at] = true;
                inventorySlots.add(slot);
            }
            return true;
        }

        /**
         * The station's own take: its quickMoveStack crafts one batch (awards the recipe, uses the ingredients, puts
         * the remainders back into the grid) and moves the result into the player's inventory. The result slot is not
         * set or cleared here. The inventory must gain the result's count for each craft, else one warning and false.
         */
        private boolean takeAtStation(ItemStack made) {
            stationMade = made;
            stationBefore = countInInventory(made);
            // tookResult is cleared only after the call returns. If the call throws, handle() still sees the flag and
            // keeps the grid as it is, since the ingredients may already be used up.
            tookResult = true;
            menu.quickMoveStack(player, menu.slots.indexOf(resultSlot));
            tookResult = false;
            int gained = stationGained();
            int count = made.getCount();
            if (gained >= count && gained % count == 0) {
                crafted += gained / count;
                return true;
            }
            NeiAddon.LOGGER.warn("Server-side crafting at the station: the inventory gained {} of {} made, stopping",
                gained, count);
            return false;
        }

        /** How many of the station's result the player's 36 slots gained since the take started. */
        int stationGained() {
            return countInInventory(stationMade) - stationBefore;
        }

        /** How many of the item (with its tags) the player's 36 slots hold. */
        private int countInInventory(ItemStack like) {
            int count = 0;
            for (Slot slot : inventory) {
                ItemStack held = slot.getItem();
                if (!held.isEmpty() && ItemStack.isSameItemSameComponents(held, like)) count += held.getCount();
            }
            return count;
        }

        /** Puts the grid back into the inventory (what does not fit stays in the grid), then syncs the menu. */
        private ChainAnswer finish(Reason reason, ResourceLocation missing, CraftingContainer container) {
            restoreGrid();
            menu.broadcastChanges();
            return answer(reason, missing);
        }

        void restoreGrid() {
            if (sophisticated) {
                // the cells go back into the inventory, then the storage; what fits nowhere stays in its cell
                List<Slot> places = new ArrayList<>(inventory);
                places.addAll(storageSlots);
                for (int i = 0; i < grid.size(); i++) {
                    if (!grid.get(i).getItem().isEmpty()) cellBack(i, places);
                }
                if (menu != null) menu.broadcastChanges();
                return;
            }
            boolean empty = true;
            for (Slot slot : grid) {
                if (!slot.getItem().isEmpty()) stash(slot);
                if (!slot.getItem().isEmpty()) empty = false;
            }
            // The vanilla result slot was set by hand above, so it must not keep showing a result the grid no longer
            // makes. The station keeps no stored result, so it has nothing to clear.
            if (!station && resultSlot != null && !grid.isEmpty()) {
                if (empty) setResult(ItemStack.EMPTY);
                else if (menu != null) menu.slotsChanged(grid.get(0).container);
            }
            if (menu != null) menu.broadcastChanges();
        }

        /**
         * Writes the result through the slot's container. FastWorkbench's result slot ignores {@code Slot.set}, so the
         * slot itself can't be used for this.
         */
        private void setResult(ItemStack stack) {
            resultSlot.container.setItem(resultSlot.getContainerSlot(), stack);
            resultSlot.setChanged();
        }

        /** Moves a grid slot's stack into the inventory; false when some of it did not fit (it stays in the slot). */
        private boolean stash(Slot slot) {
            ItemStack stack = slot.getItem();
            insert(stack, false);
            slot.set(stack.isEmpty() ? ItemStack.EMPTY : stack);
            return stack.isEmpty();
        }

        /**
         * One item of the slots the ingredient accepts: a chosen one first, then the one the slot had last, and the
         * first slot of the list among equals.
         */
        private Slot pick(List<Slot> from, Ingredient ingredient, Set<Item> preferred, Item previous) {
            Slot best = null;
            int bestScore = -1;
            for (Slot slot : from) {
                ItemStack stack = slot.getItem();
                if (stack.isEmpty() || !ingredient.test(stack)) continue;
                int score = (preferred.contains(stack.getItem()) ? 2 : 0) + (stack.getItem() == previous ? 1 : 0);
                if (score > bestScore) {
                    best = slot;
                    bestScore = score;
                }
            }
            return best;
        }

        /** The id of the item that ran out: the chosen one that fits the ingredient, else the ingredient's first. */
        private ResourceLocation missingId(Ingredient ingredient) {
            for (ResourceLocation id : step.preferred()) {
                Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item != null && ingredient.test(new ItemStack(item))) return id;
            }
            for (ItemStack stack : ingredient.getItems()) {
                if (!stack.isEmpty()) return BuiltInRegistries.ITEM.getKey(stack.getItem());
            }
            return null;
        }

        /** Whether the inventory has room for the whole stack (what {@link #insert} would place). */
        private boolean roomFor(ItemStack stack) {
            return freeFor(stack) >= stack.getCount();
        }

        /** How much of the stack the inventory has room for, counted up to the stack's size. */
        private int freeFor(ItemStack stack) {
            int free = 0;
            for (Slot slot : inventory) {
                ItemStack held = slot.getItem();
                if (held.isEmpty()) {
                    if (slot.mayPlace(stack)) free += Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize());
                } else if (stack.isStackable() && ItemStack.isSameItemSameComponents(held, stack)) {
                    free += Math.max(0, slot.getMaxStackSize(held) - held.getCount());
                }
                if (free >= stack.getCount()) return stack.getCount();
            }
            return free;
        }

        /**
         * AbstractContainerMenu.moveItemStackTo over the player's main slots: first onto stacks of the same item, then
         * into empty slots. {@code reverse} walks from the last slot, as the result slot's shift-click does. The stack
         * shrinks by what was moved.
         */
        private void insert(ItemStack stack, boolean reverse) {
            int count = inventory.size();
            if (stack.isStackable()) {
                for (int n = 0; n < count && !stack.isEmpty(); n++) {
                    Slot slot = inventory.get(reverse ? count - 1 - n : n);
                    ItemStack held = slot.getItem();
                    if (held.isEmpty() || !ItemStack.isSameItemSameComponents(stack, held)) continue;
                    int room = slot.getMaxStackSize(held) - held.getCount();
                    if (room <= 0) continue;
                    int moved = Math.min(room, stack.getCount());
                    held.grow(moved);
                    stack.shrink(moved);
                    slot.setChanged();
                }
            }
            for (int n = 0; n < count && !stack.isEmpty(); n++) {
                Slot slot = inventory.get(reverse ? count - 1 - n : n);
                if (!slot.getItem().isEmpty() || !slot.mayPlace(stack)) continue;
                int moved = Math.min(stack.getCount(), slot.getMaxStackSize(stack));
                // the slot is written before the stack shrinks; if the write throws, the slot is read back and the
                // stack shrinks only when the slot holds the part (the write stored before it threw)
                ItemStack part = stack.copyWithCount(moved);
                try {
                    slot.setByPlayer(part);
                } catch (RuntimeException | LinkageError e) {
                    if (sameStack(slot.getItem(), part)) stack.shrink(moved);
                    throw e;
                }
                stack.shrink(moved);
                slot.setChanged();
            }
        }

        /**
         * The result slot branch of CraftingMenu.quickMoveStack: onCraftedBy, move the stack into the inventory,
         * onQuickCraft, clear the slot, onTake (remainders, tool damage, advancements, the crafting event). Whatever
         * did not fit is dropped as vanilla does, though the room check before makes that rare.
         */
        private boolean take(Level level) {
            ItemStack stack = resultSlot.getItem();
            ItemStack before = stack.copy();
            if (menu.getClass() == CraftingMenu.class) stack.getItem().onCraftedBy(stack, level, player);
            insert(stack, true);
            if (stack.getCount() == before.getCount()) return false;
            tookResult = true;
            resultSlot.onQuickCraft(stack, before);
            // always empty: a leftover that didn't fit is dropped below and must not stay takeable in the slot
            setResult(ItemStack.EMPTY);
            resultSlot.onTake(player, stack);
            if (!stack.isEmpty()) player.drop(stack, false);
            tookResult = false;
            return true;
        }
    }

    /**
     * A read-only copy of the station's (or the AE2 terminal's, or the lectern's) 3x3 grid, for the recipe's own check:
     * that grid is not a crafting container by name, so the recipe reads this instead. The stacks are copies, so a
     * recipe that changes them cannot change the station's grid; anything that tries to write to it fails, which stops
     * the craft.
     */
    private static final class GridView implements CraftingContainer {

        private final List<ItemStack> items = new ArrayList<>();

        GridView(List<Slot> grid) {
            for (Slot slot : grid) items.add(slot.getItem().copy());
        }

        @Override
        public int getWidth() {
            return STATION_SIDE;
        }

        @Override
        public int getHeight() {
            return STATION_SIDE;
        }

        @Override
        public List<ItemStack> getItems() {
            return Collections.unmodifiableList(items);
        }

        @Override
        public int getContainerSize() {
            return items.size();
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : items) {
                if (!stack.isEmpty()) return false;
            }
            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            return index >= 0 && index < items.size() ? items.get(index) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            throw new UnsupportedOperationException("the station's grid copy is read-only");
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            throw new UnsupportedOperationException("the station's grid copy is read-only");
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            throw new UnsupportedOperationException("the station's grid copy is read-only");
        }

        @Override
        public void setChanged() {}

        @Override
        public boolean stillValid(Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            throw new UnsupportedOperationException("the station's grid copy is read-only");
        }

        @Override
        public void fillStackedContents(StackedContents contents) {
            for (ItemStack stack : items) contents.accountSimpleStack(stack);
        }
    }
}
