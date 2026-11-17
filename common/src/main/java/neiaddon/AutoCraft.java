package neiaddon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import neiaddon.chain.ChainAutocraft;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.TickEvent;

/**
 * NEI's autocrafting (Shift + C): works through a crafting chain recipe by recipe. For each craft it has JEI's recipe
 * transfer move the ingredients into the open crafting grid, then shift-clicks the result slot. What to craft, and in
 * which order, is decided by {@link ChainAutocraft} (a port of NEI's AutoCraftingManager and RecipeChainIterator).
 *
 * <p>
 * NEI does the clicks in one go on a worker thread. Here the server has to answer between a transfer and the click, so
 * one step is done per client tick and the next waits for the result slot to fill (and later to empty). While more than
 * one craft of a recipe is wanted the grid is filled with as many sets as fit and the result shift-clicked once; when a
 * full grid would make more than is wanted, the rest of that recipe is crafted one set at a time. It stops when
 * the screen closes or another container opens, or when the result cannot be moved (inventory full).
 */
public final class AutoCraft {

    /** Ticks to wait for the server before giving up on a craft. */
    private static final int TIMEOUT = 40;

    private enum Phase {
        NEXT,
        WAIT_RESULT,
        WAIT_ONE_SET,
        WAIT_CLEAR,
        CLICKS,
        CLEANUP,
        GIVE_BACK,
        RESTORE,
        TOPUP,
        SYNC,
        RECHECK,
        AE2_CLEAR
    }

    private static ChainAutocraft walker;
    private static ChainMath math;
    private static AbstractContainerMenu menu;
    private static Phase phase = Phase.NEXT;
    private static int waited;
    private static String current;
    /** Whether the last transfer asked for as many sets as fit. */
    private static boolean full;
    /** Per recipe: how many sets the last full grid held, so the tail of a recipe goes straight to one set at a time. */
    private static final Map<String, Long> fullSets = new HashMap<>();
    private static List<Slot> gridSlots = List.of();
    private static final Map<Slot, ItemStack> before = new HashMap<>();
    /** The menu's state id sent with our last click; the server's answer replaces it (see forceAnswer). */
    private static int clickState;
    /** Faster Autocrafting: crafts still to make by clicking the result, and whether a click waits for the server. */
    private static long clicksLeft;
    private static boolean awaiting, lastWasCraft;
    /**
     * An AE2 crafting terminal is open (see {@link Ae2Crafting}): the network counts as inventory, the grid fills from it
     * one set at a time, each craft is one AE2 craft action onto the cursor (AE2 refills the grid from the network, so a
     * craft is counted by the cursor, not by the grid), and the cleanup puts the grid back into the network.
     */
    private static boolean ae2;
    /** AE2: the cursor before the last craft action, the result count it should add, and whether the grid was cleared. */
    private static ItemStack ae2Carried = ItemStack.EMPTY;
    private static int ae2Adds;
    private static boolean ae2Cleared;
    /** Grid slots the cleanup already shift-clicked (a slot that does not empty is not tried again). */
    private static final java.util.Set<Slot> cleaned = new java.util.HashSet<>();
    /**
     * AE2: recipes skipped in this run because the grid made another item, mapped to what the end of the run says about
     * them. A skipped recipe is not tried again in the run.
     */
    private static final Map<String, String> skipped = new java.util.LinkedHashMap<>();
    /** AE2: recipes whose missing ingredient was already logged in this run. */
    private static final java.util.Set<String> ae2Logged = new java.util.HashSet<>();
    /** AE2: recipes whose last transfer was refused and is asked again after {@link #AE2_RETRY_TICKS}. */
    private static final java.util.Set<String> ae2Retried = new java.util.HashSet<>();
    private static final int AE2_RETRY_TICKS = 10;
    private static int ae2RetryWait;
    /** AE2: the cursor's stack is going into the network (see {@link #ae2PutWait}), and how often in a row it was tried. */
    private static boolean ae2Putting;
    private static int ae2PutTries;
    /** How many times in a row the network is asked to take the cursor's stack before the run stops with it on the cursor. */
    private static final int AE2_PUT_TRIES = 3;
    /** AE2 after a skip: the cursor's stack the clear last deposited, and how often in a row it was tried (see {@link #ae2Clear}). */
    private static ItemStack ae2ClearCarried = ItemStack.EMPTY;
    private static int ae2ClearDeposits;
    /** How many deposits of the same cursor stack the clear makes before it stops (as its timeout does). */
    private static final int AE2_CLEAR_DEPOSITS = 3;
    /** Whether the chat line naming the skipped recipes was sent in this run (see {@link #saySkipped}). */
    private static boolean skippedSaid;

    private AutoCraft() {}

    /** The open screen's menu when it has a crafting result slot (inventory, crafting table...), else null. */
    private static AbstractContainerMenu craftingMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || Calculator.runtime == null || !(McCompat.screen() instanceof AbstractContainerScreen<?> screen)) return null;
        AbstractContainerMenu open = screen.getMenu();
        return resultSlot(open) != null && mc.player.containerMenu == open ? open : null;
    }

    public static boolean available() {
        return AddonConfig.AUTOCRAFTING.get() && craftingMenu() != null;
    }

    public static boolean processing() {
        return walker != null;
    }

    private static Slot resultSlot(AbstractContainerMenu m) {
        return CraftSlots.result(m);
    }

    /** AE2: whether the last {@link #inventory()} could read the network's items. */
    private static boolean networkRead;

    /**
     * What the player can craft with: every slot of the open menu except the result, and the grid of a crafting upgrade
     * (Sophisticated Storage keeps it outside the menu's slot list). NEI's getInventoryItems.
     */
    private static Map<String, Long> inventory() {
        Map<String, Long> items = new HashMap<>();
        Player player = Minecraft.getInstance().player;
        if (ae2) {
            // the player's inventory, the grid (it goes back into the network before a fill) and the network; not the
            // terminal's other slots (view cells, upgrades)
            for (Slot slot : menu.slots) {
                if (slot.hasItem() && (slot.container instanceof Inventory || gridSlots.contains(slot))) {
                    items.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
                }
            }
            Map<String, Long> network = Ae2Crafting.networkItems(McCompat.screen());
            networkRead = network != null;
            if (network != null) network.forEach((id, n) -> items.merge(id, n, Long::sum));
            return items;
        }
        List<Slot> slots = new ArrayList<>(menu.slots);
        slots.addAll(CraftSlots.upgradeSlots(menu));
        for (Slot slot : slots) {
            if (slot.hasItem() && !CraftSlots.isResult(slot) && slot.mayPickup(player)) {
                items.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
            }
        }
        return items;
    }

    /**
     * Starts crafting the chain. {@code ignoreInventory} (NEI: no Ctrl) crafts the wanted amount again even if the
     * inventory already holds it; with Ctrl only what is missing is crafted.
     */
    public static boolean start(ChainMath chain, boolean ignoreInventory) {
        AbstractContainerMenu open = AddonConfig.AUTOCRAFTING.get() ? craftingMenu() : null;
        if (chain == null || open == null) {
            NeiAddon.LOGGER.info("Autocrafting not started: chain={} craftingGridOpen={}", chain != null, open != null);
            return false;
        }
        if (CraftSlots.refillsGrid(open)) {
            // the upgrade puts new ingredients into the grid after every craft: the crafts could not be counted, and a
            // shift-click would go on crafting from the storage
            NeiAddon.LOGGER.info("Autocrafting not started: the crafting upgrade refills its grid from the storage");
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§6[NEI Features] §eAutocrafting can't run while the crafting upgrade refills its grid from the storage."));
            return false;
        }
        if (Ae2Crafting.isTerminal(open)
            && (Ae2Crafting.broken() || CraftSlots.grid(open).isEmpty() || Ae2Crafting.networkItems(McCompat.screen()) == null)) {
            // without the grid or the network's items the chain would be planned wrong
            NeiAddon.LOGGER.info("Autocrafting not started: the AE2 terminal's grid or items could not be read");
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§6[NEI Features] §eAutocrafting can't read this AE2 terminal."));
            return false;
        }
        stop();
        menu = open;
        ae2 = Ae2Crafting.isTerminal(open);
        ae2Cleared = false;
        gridSlots = craftingGrid(open);
        snapshotLayout();
        clearStorage();
        fullSets.clear();
        math = chain;
        math.createMasterRoot();
        if (ignoreInventory) ignoreInventory();
        math.createMasterRoot();
        askedFor.clear();
        askedAmount.clear();
        for (ChainItem item : math.recipeIngredients) {
            if (ChainMath.ROOT_RECIPE_ID.equals(item.recipeId)) {
                askedFor.add(item.item);
                askedAmount.merge(item.item, item.amount(), Long::sum);
            }
        }
        walker = new ChainAutocraft(math, new ChainAutocraft.Host() {

            @Override
            public Map<String, Long> inventory() {
                return AutoCraft.inventory();
            }

            @Override
            public boolean canCraft(String recipeId) {
                if (skipped.containsKey(recipeId)) return false;
                RecipeLookup.Target<?> target = target(recipeId);
                boolean can = target != null && target.canTransfer(menu, Minecraft.getInstance().player);
                if (!can) NeiAddon.LOGGER.info("Autocrafting: cannot craft {} now (recipe found: {})", recipeId, target != null);
                if (can && ae2) {
                    // AE2 fills the grid from the network whatever it holds, so one craft's ingredients are checked here
                    Map<String, Long> have = AutoCraft.inventory();
                    String missing = ae2Missing(recipeId, have);
                    // a tool by item, any wear: a recipe whose tool the chain makes later waits for it (else the grid
                    // makes something else and the recipe is skipped for the run)
                    if (missing == null) missing = ae2MissingTool(recipeId, have);
                    if (missing != null) {
                        can = false;
                        if (ae2Logged.add(recipeId)) NeiAddon.LOGGER.info("Autocrafting: skipping {} for now, {} is not in the inventory, the network or the grid", recipeId, missing);
                    }
                }
                lastCan.put(recipeId, can);
                return can;
            }
        });
        // first make this side's view of the screen the server's (an item seen here but gone there makes JEI's transfer fail)
        phase = Phase.SYNC;
        syncRetries.clear();
        singleClicks.clear();
        lastCan.clear();
        rechecked.clear();
        craftedBy.clear();
        skipped.clear();
        skippedSaid = false;
        ae2Logged.clear();
        ae2Putting = false;
        ae2PutTries = 0;
        ae2Refused.clear();
        ae2Retried.clear();
        ae2RetryWait = 0;
        NeiAddon.LOGGER.info("Autocrafting started: {}", math.outputRecipes);
        return true;
    }

    public static void stop() {
        if (walker != null) saySkipped(Minecraft.getInstance());
        gridSlots = List.of();
        layout.clear();
        pending.clear();
        topSlots.clear();
        topTarget.clear();
        before.clear();
        cleaned.clear();
        clearStorage();
        awaiting = false;
        ae2Putting = false;
        ae2Retried.clear();
        ae2RetryWait = 0;
        walker = null;
        math = null;
        menu = null;
        ae2 = false;
        ae2Carried = ItemStack.EMPTY;
    }

    /** NEI's autocraftingIgnoreInventory: wanted items the inventory already covers are asked for once more on top. */
    private static void ignoreInventory() {
        Map<String, Long> have = inventory();
        for (int i = 0; i < math.recipeIngredients.size(); i++) {
            ChainItem item = math.recipeIngredients.get(i);
            if (item.amount() > 0 && ChainMath.ROOT_RECIPE_ID.equals(item.recipeId)) {
                long amount = have.getOrDefault(item.item, 0L);
                if (amount >= item.amount()) {
                    math.recipeIngredients.set(
                        i,
                        new ChainItem(
                            item.item,
                            item.recipeId,
                            item.type,
                            amount + item.amount() - amount % item.amount(),
                            1,
                            item.options,
                            item.payload));
                }
            }
        }
    }

    private static RecipeLookup.Target<?> target(String recipeId) {
        ItemStack hint = ItemStack.EMPTY;
        for (ChainItem item : math.recipeResults) {
            if (recipeId.equals(item.recipeId)) {
                hint = FluidStacks.stackOf(item.item);
                break;
            }
        }
        return RecipeLookup.findTarget(recipeId, hint);
    }

    @SubscribeEvent
    static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (walker == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || craftingMenu() != menu) {
            stop();
            return;
        }
        Slot result = resultSlot(menu);
        // a finished craft goes on to the next transfer in the same tick: the server has answered, nothing to wait for
        for (int steps = 0; steps < 3 && walker != null; steps++) {
            Phase was = phase;
            switch (phase) {
                case NEXT -> {
                    if (ae2RetryWait > 0) {
                        ae2RetryWait--;
                        return;
                    }
                    String recipeId = walker.next();
                    if (recipeId == null) {
                        // done: whatever is still on the cursor or in the grid goes back into the inventory
                        reportUnfinished(mc);
                        startCleanup();
                        return;
                    }
                    // a retry waits for this same recipe: a mark left by a recipe the chain moved on from is stale
                    ae2Retried.removeIf(id -> !id.equals(recipeId));
                    RecipeLookup.Target<?> target = target(recipeId);
                    // batching: as many sets as the grid takes while more crafts are wanted than the last full grid of this
                    // recipe held (unknown at first: try); one set otherwise
                    full = !ae2 && !gridSlots.isEmpty() && walker.remaining() > 1
                        && (AddonConfig.AUTOCRAFTING_FAST.get() || walker.remaining() >= fullSets.getOrDefault(recipeId, 2L));
                    if (target == null || !target.transfer(menu, mc.player, full)) {
                        String why = target == null ? "recipe not found" : refusedWhy(target, mc);
                        if (ae2 && target != null && ae2Retried.add(recipeId)) {
                            // AE2's view of the network can lag just behind a put: asked once more after a short wait
                            NeiAddon.LOGGER.info("Autocrafting: transfer of {} was refused ({}); asking again shortly", recipeId, why);
                            ae2RetryWait = AE2_RETRY_TICKS;
                            return;
                        }
                        NeiAddon.LOGGER.info("Autocrafting: transfer of {} was refused ({})", recipeId, why);
                        // a later pass of the chain may offer it again: then it gets its retry again
                        ae2Retried.remove(recipeId);
                        walker.abandon();
                        return;
                    }
                    ae2Retried.remove(recipeId);
                    current = recipeId;
                    phase = Phase.WAIT_RESULT;
                    waited = 0;
                }
                case WAIT_RESULT -> {
                    if (result.hasItem()) {
                        if (wrongResult(result)) return;
                        if (ae2) {
                            // AE2 refills the grid from the network after each craft: one craft action per wanted craft
                            clicksLeft = walker.remaining();
                            awaiting = false;
                            phase = Phase.CLICKS;
                            waited = 0;
                            clicks(mc, result);
                            return;
                        }
                        if (full && gridSets() == 1 && walker.remaining() > 1 && startTopUp()) return;
                        long sets = full ? gridSets() : 1;
                        if (full) fullSets.put(current, sets);
                        if (full && sets > walker.remaining() && AddonConfig.AUTOCRAFTING_FAST.get()) {
                            // Faster Autocrafting: click the result once per craft that is still wanted
                            clicksLeft = walker.remaining();
                            awaiting = false;
                            phase = Phase.CLICKS;
                            waited = 0;
                            clicks(mc, result);
                            return;
                        }
                        if (full && sets > walker.remaining()) {
                            // trimming the tail: the grid holds more sets than crafts are left, so put one set in instead
                            // (JEI empties the grid first) and do the rest of this recipe one craft at a time
                            RecipeLookup.Target<?> target = target(current);
                            full = false;
                            if (target == null || !target.transfer(menu, mc.player, false)) {
                                NeiAddon.LOGGER.info("Autocrafting: transfer of {} was refused", current);
                                walker.abandon();
                                phase = Phase.NEXT;
                                return;
                            }
                            phase = Phase.WAIT_ONE_SET;
                            waited = 0;
                            return;
                        }
                        craft(mc, result);
                    } else if (++waited > (syncRetries.containsKey(current) ? TIMEOUT : FIRST_RESULT_TICKS)) {
                        // a first transfer gets less time: when it did nothing, the screen is fetched and it is tried again
                        if (syncRetries.merge(current, 1, Integer::sum) <= 1) {
                            // JEI's transfer did nothing: most likely this side saw an item the server does not have
                            NeiAddon.LOGGER.info("Autocrafting: no result after the transfer of {}, fetching the screen from the server and trying again", current);
                            phase = Phase.SYNC;
                        } else {
                            NeiAddon.LOGGER.info("Autocrafting: no result after the transfer of {}, giving up on this recipe", current);
                            walker.abandon();
                            phase = Phase.NEXT;
                        }
                    }
                }
                case WAIT_ONE_SET -> {
                    if (result.hasItem() && gridSets() == 1) {
                        if (wrongResult(result)) return;
                        craft(mc, result);
                    } else if (++waited > TIMEOUT) {
                        NeiAddon.LOGGER.info("Autocrafting: the grid did not go back to one set, giving up on this recipe");
                        walker.abandon();
                        phase = Phase.NEXT;
                    }
                }
                case WAIT_CLEAR -> {
                    // only the server's answer counts: right after the click this side already shows the result slot empty
                    // (its own guess of the click), and counting the crafts from that guess would count one instead of all
                    // (the click always gets an answer, see forceAnswer)
                    if (menu.getStateId() != clickState && !result.hasItem()) {
                        noteCrafted(craftsDone());
                        phase = Phase.NEXT;
                    } else if (++waited > TIMEOUT) {
                        if (menu.getStateId() != clickState && result.hasItem() && craftsDoneExact() == 0 && roomFor(result.getItem())
                            && singleClicks.add(current)) {
                            // the server answered but the shift-click crafted nothing (some recipes or crafting-table
                            // mods only take the result a craft at a time): craft this recipe by clicking the result
                            NeiAddon.LOGGER.info("Autocrafting: shift-clicking the result of {} did nothing, crafting it one click at a time", current);
                            clicksLeft = Math.min(walker.remaining(), Math.max(1, gridSets()));
                            awaiting = false;
                            phase = Phase.CLICKS;
                            waited = 0;
                            clicks(mc, result);
                            return;
                        }
                        stoppedOnResult(mc, result);
                        startCleanup();
                    }
                }
                case CLICKS -> clicks(mc, result);
                case CLEANUP -> cleanup(mc);
                case GIVE_BACK -> giveBack(mc);
                case RESTORE -> restore(mc);
                case TOPUP -> topUp(mc, result);
                case AE2_CLEAR -> ae2Clear(mc);
                case RECHECK -> {
                    // the screen was fetched from the server to see the result slot as the server has it
                    if (menu.getStateId() == clickState && ++waited <= TIMEOUT) return;
                    if (settle++ < SETTLE_TICKS) return;
                    awaiting = false;
                    waited = 0;
                    phase = resumeAfterRecheck;
                    return;
                }
                case SYNC -> {
                    if (awaiting) {
                        if (menu.getStateId() == clickState && ++waited <= TIMEOUT) return;
                        // the id also moves with updates the server sent before the answer: give the answer itself a
                        // moment to arrive before JEI looks at the screen again
                        if (settle++ < SETTLE_TICKS) return;
                        awaiting = false;
                        if (!storageTaken) snapshotStorage();
                        phase = Phase.NEXT;
                    } else if (!syncClick(mc)) {
                        if (!storageTaken) snapshotStorage();
                        phase = Phase.NEXT;
                    } else {
                        return;
                    }
                }
            }
            if (was != Phase.WAIT_CLEAR || phase != Phase.NEXT) break;
        }
    }

    /**
     * Is the result slot an item the recipe being crafted does not make? Another recipe with the same ingredients can
     * take priority in the grid (a mod's duplicate of a vanilla tool), and crafting that would never get the wanted item,
     * so the chain would craft it again and again. Only the item is compared (a result's NBT can vary). When it differs
     * this gives up on the recipe, tells the player and starts the cleanup (AE2: skips the recipe and goes on, see
     * {@link #ae2Skip}); returns true then (do not craft).
     */
    /** Recipes whose wrong-looking result was already checked with the server once, and where to go on after the check. */
    private static final java.util.Set<String> rechecked = new java.util.HashSet<>();
    private static Phase resumeAfterRecheck = Phase.NEXT;

    private static boolean wrongResult(Slot result) {
        if (math == null || walker == null || current == null || !result.hasItem()) return false;
        ItemStack got = result.getItem();
        ItemStack expected = ItemStack.EMPTY;
        for (ChainItem item : math.recipeResults) {
            if (!current.equals(item.recipeId)) continue;
            ItemStack want = FluidStacks.stackOf(item.item);
            // a result that cannot be told is not a reason to stop
            if (want.isEmpty() || want.getItem() == got.getItem()) return false;
            if (expected.isEmpty()) expected = want;
        }
        if (expected.isEmpty()) return false;
        if (rechecked.add(current) && syncClick(Minecraft.getInstance())) {
            // this side's result slot can be out of date (a crafting-table mod that works the result out on this side
            // too, or an update still on its way): fetch the screen from the server and look again before stopping
            NeiAddon.LOGGER.info("Autocrafting: the result slot shows {} while crafting {}; checking with the server", got.getHoverName()
                .getString(), current);
            resumeAfterRecheck = phase;
            phase = Phase.RECHECK;
            return true;
        }
        walker.abandon();
        String made = got.getHoverName().getString();
        String wanted = FluidStacks.name(expected).getString();
        if (made.equals(wanted)) {
            // two items with the same name (another mod's copy of an item): their ids tell them apart
            made += " (" + BuiltInRegistries.ITEM.getKey(got.getItem()) + ")";
            wanted += " (" + BuiltInRegistries.ITEM.getKey(expected.getItem()) + ")";
        }
        if (ae2) {
            ae2Skip(made, wanted);
            return true;
        }
        fail("the grid makes " + made + " instead of " + wanted + " (another recipe with the same ingredients takes priority)");
        return true;
    }

    /**
     * AE2: the grid makes another item than the recipe's, so the recipe is skipped for the rest of the run instead of stopping
     * the chain. Nothing is crafted: the grid goes back into the network (then {@link Phase#AE2_CLEAR} waits for it to be
     * empty), and the end of the run names what did not reach the grid. Without the clear it stops as on other grids.
     */
    private static void ae2Skip(String made, String wanted) {
        String names = ae2NotInGrid(current);
        String text = wanted + ": the grid made " + made + (names.isEmpty()
            ? " (every ingredient is in the grid; another recipe takes them)"
            : "; " + names + " did not reach the grid");
        skipped.put(current, text);
        NeiAddon.LOGGER.info("Autocrafting: skipped {}, the grid made {} instead of {}", current, made, wanted);
        clicksLeft = 0;
        awaiting = false;
        if (!Ae2Crafting.clearGrid(menu)) {
            ae2GridLeft();
            stop();
            return;
        }
        waited = 0;
        ae2ClearCarried = ItemStack.EMPTY;
        ae2ClearDeposits = 0;
        phase = Phase.AE2_CLEAR;
    }

    /**
     * AE2 after a skip: the cursor is put away and the grid is waited on to go empty, then the walker goes on. The cursor
     * gets at most {@link #AE2_CLEAR_DEPOSITS} deposits while it stays the same stack; after that the clear stops as its
     * timeout does.
     */
    private static void ae2Clear(Minecraft mc) {
        if (ae2PutWait()) return;
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) stop();
                return;
            }
            awaiting = false;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            if (!ItemStack.matches(carried, ae2ClearCarried)) {
                ae2ClearCarried = carried.copy();
                ae2ClearDeposits = 0;
            }
            if (++ae2ClearDeposits > AE2_CLEAR_DEPOSITS) {
                // no cleanup: its cursor step would try the same put again
                sayStopped("what is on the cursor could not be put away");
                stop();
                return;
            }
            deposit(mc, carried);
            return;
        }
        if (gridSlots.stream().anyMatch(Slot::hasItem)) {
            if (++waited <= TIMEOUT) return;
            ae2GridLeft();
            stop();
            return;
        }
        phase = Phase.NEXT;
    }

    /**
     * AE2: the ingredients of one craft of the recipe that the grid holds too little of (names, comma separated). A tool
     * counts as there when the grid holds the same item (its id can differ by wear, see {@link #ae2Missing}).
     */
    private static String ae2NotInGrid(String recipeId) {
        Map<String, Long> have = new HashMap<>();
        for (Slot slot : gridSlots) {
            if (slot.hasItem()) have.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
        }
        List<String> names = new ArrayList<>();
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor() || isFluid(ingredient)) continue;
            String name = FluidStacks.name(FluidStacks.stackOf(ingredient.item)).getString();
            if (Tools.isTool(FluidStacks.stackOf(ingredient.item))) {
                if (!gridHoldsItemOf(ingredient)) names.add(name);
                continue;
            }
            long in = 0;
            for (String option : candidates(ingredient)) in += have.getOrDefault(option, 0L);
            if (in < ingredient.factor) names.add(name);
        }
        return String.join(", ", names);
    }

    /** AE2: whether the grid holds an item of the ingredient's kind (the same registry item as one of its candidates). */
    private static boolean gridHoldsItemOf(ChainItem ingredient) {
        java.util.Set<String> items = new java.util.HashSet<>();
        for (String id : candidates(ingredient)) items.add(plainId(id));
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && items.contains(plainId(RecipeData.idOf(slot.getItem())))) return true;
        }
        return false;
    }

    /**
     * AE2: the name of an ingredient of the recipe (of the chain's own recipe) the inventory (player's, grid and network)
     * does not hold enough of for one craft, or null when all are there. Tools and fluids are left out: a worn tool can
     * have another id than the recipe's option (its craft is then checked by the wrong-result skip, which names a missing
     * tool), and a fluid is not an item. Each ingredient's candidates ({@link #candidates}) share the inventory; the ones
     * with fewest candidates get their items first (a slot that takes only X gets X before one that takes X or Y), and each
     * takes its amount from the items it accepts, so one item is not counted for two ingredients.
     */
    private static String ae2Missing(String recipeId) {
        return ae2Missing(recipeId, inventory());
    }

    private static String ae2Missing(String recipeId, Map<String, Long> inventory) {
        Map<String, Long> have = new HashMap<>(inventory);
        List<ChainItem> ingredients = new ArrayList<>();
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor() || leftToTransfer(ingredient)) continue;
            ingredients.add(ingredient);
        }
        // a stable sort: ingredients with as many candidates keep the chain's order
        ingredients.sort(java.util.Comparator.comparingInt(i -> candidates(i).size()));
        for (ChainItem ingredient : ingredients) {
            long need = ingredient.factor;
            long in = 0;
            for (String option : candidates(ingredient)) in += have.getOrDefault(option, 0L);
            if (in < need) return FluidStacks.name(FluidStacks.stackOf(ingredient.item)).getString();
            for (String option : candidates(ingredient)) {
                long n = have.getOrDefault(option, 0L), take = Math.min(n, need);
                have.put(option, n - take);
                need -= take;
            }
        }
        return null;
    }

    /** Why a transfer was refused, for the log: {@code whyNot} when it names something, else a note that it found nothing. */
    private static String refusedWhy(RecipeLookup.Target<?> target, Minecraft mc) {
        try {
            String why = target.whyNot(menu, mc.player);
            return why != null ? why : "nothing missing on a second look";
        } catch (RuntimeException | LinkageError e) {
            return "the check failed: " + e;
        }
    }

    /**
     * AE2: the name of a tool of the recipe that is nowhere in the inventory (player's, grid and network), in any wear
     * (compared by item, without the subtype), or null; also null when the network's items can't be read. For the
     * end-of-run line: {@link #ae2Missing} leaves tools out.
     */
    private static String ae2MissingTool(String recipeId) {
        return ae2MissingTool(recipeId, inventory());
    }

    /** The same with the inventory already read ({@link #inventory()}, which also sets {@link #networkRead}). */
    private static String ae2MissingTool(String recipeId, Map<String, Long> inventory) {
        if (!networkRead) return null;
        java.util.Set<String> have = new java.util.HashSet<>();
        for (String id : inventory.keySet()) have.add(plainId(id));
        if (!menu.getCarried().isEmpty()) have.add(plainId(RecipeData.idOf(menu.getCarried())));
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor() || isFluid(ingredient)
                || !Tools.isTool(FluidStacks.stackOf(ingredient.item))) continue;
            if (candidates(ingredient).stream().noneMatch(id -> have.contains(plainId(id)))) {
                return FluidStacks.name(FluidStacks.stackOf(ingredient.item)).getString();
            }
        }
        return null;
    }

    /** AE2: the ids an ingredient accepts: its options (tags) and its own item, each once. */
    private static java.util.Set<String> candidates(ChainItem ingredient) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>(ingredient.options);
        ids.add(ingredient.item);
        return ids;
    }

    /** AE2: a fluid ingredient (the chain marks it by its id, see {@link FluidStacks}); the grid never holds one. */
    private static boolean isFluid(ChainItem ingredient) {
        ItemStack stack = FluidStacks.stackOf(ingredient.item);
        return FluidStacks.isFluidId(ingredient.item) || FluidStacks.isFluid(stack) || FluidStacks.isMilli(stack);
    }

    /** AE2: an ingredient the item check leaves to the transfer: a fluid, or a tool (a worn tool's id is not its option's). */
    private static boolean leftToTransfer(ChainItem ingredient) {
        return isFluid(ingredient) || Tools.isTool(FluidStacks.stackOf(ingredient.item));
    }

    /** An id without its subtype mark: the registry item a (possibly worn, subtyped) id stands for. */
    private static String plainId(String id) {
        int at = id.indexOf(StackIds.MARK);
        return at < 0 ? id : id.substring(0, at);
    }

    /** Shift-clicks the result: vanilla crafts as long as the grid has a full set (and the inventory has room). */
    private static void craft(Minecraft mc, Slot result) {
        before.clear();
        for (Slot slot : gridSlots) before.put(slot, slot.getItem().copy());
        forceAnswer();
        mc.gameMode.handleInventoryMouseClick(menu.containerId, result.index, 0, ClickType.QUICK_MOVE, mc.player);
        phase = Phase.WAIT_CLEAR;
        waited = 0;
    }

    /**
     * How many crafts the last shift-click did, from the grid before and after: each craft takes one item from every
     * filled slot. A slot whose item changed (a bucket left behind) or emptied counts as all used.
     */
    private static long craftsDone() {
        long done = Long.MAX_VALUE;
        for (Map.Entry<Slot, ItemStack> e : before.entrySet()) {
            ItemStack was = e.getValue();
            // a tool is not used up (it wears, or breaks on its last craft): the other slots tell how many crafts were done
            if (was.isEmpty() || Tools.isTool(was)) continue;
            ItemStack now = e.getKey().getItem();
            long left = ItemStack.isSameItemSameTags(was, now) ? now.getCount() : 0;
            done = Math.min(done, was.getCount() - left);
        }
        return done == Long.MAX_VALUE || done < 1 ? 1 : done;
    }

    /** How many full sets the crafting grid holds: the smallest stack among its filled slots. */
    private static long gridSets() {
        long sets = Long.MAX_VALUE;
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && !Tools.isTool(slot.getItem())) sets = Math.min(sets, slot.getItem().getCount());
        }
        return sets == Long.MAX_VALUE ? 0 : sets;
    }

    /** The slots of the menu's crafting grid, or none if unknown (see {@link CraftSlots#grid}). */
    private static List<Slot> craftingGrid(AbstractContainerMenu m) {
        return CraftSlots.grid(m);
    }

    // ---------------------------------------------------------------- Faster Autocrafting: exact counts by clicking

    /**
     * One step of crafting {@link #clicksLeft} items from a full grid by clicking the result slot (each click crafts once,
     * onto the cursor). Every click waits for the server's answer (the menu's state id moves) and is checked: a craft
     * click must have used one set of the grid. The cursor is emptied into the inventory whenever the next result would
     * not fit on it, and at the end; the grid's unused sets stay for the next transfer (JEI empties the grid first) or the
     * cleanup. Anything unexpected stops autocrafting through the cleanup.
     */
    private static void clicks(Minecraft mc, Slot result) {
        if (ae2) {
            ae2Clicks(mc, result);
            return;
        }
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) fail("the server did not answer a click");
                return;
            }
            awaiting = false;
            if (lastWasCraft) {
                long done = craftsDoneExact();
                if (done != 1) {
                    fail("a click on the result crafted " + done + " times instead of once");
                    return;
                }
                noteCrafted(1);
                clicksLeft--;
            }
        }
        ItemStack carried = menu.getCarried();
        if (clicksLeft <= 0) {
            if (!carried.isEmpty()) {
                deposit(mc, carried);
                return;
            }
            phase = Phase.NEXT;
            return;
        }
        ItemStack out = result.getItem();
        if (out.isEmpty()) {
            fail("the result slot is empty although sets are left in the grid");
            return;
        }
        if (wrongResult(result)) return;
        boolean fits = carried.isEmpty()
            || ItemStack.isSameItemSameTags(carried, out) && carried.getCount() + out.getCount() <= carried.getMaxStackSize();
        if (!fits) {
            deposit(mc, carried);
            return;
        }
        snapshot();
        send(mc, result.index, false);
        lastWasCraft = true;
    }

    /**
     * {@link #clicks} at an AE2 terminal: each craft is AE2's craft action, answered when the cursor changes, and must have
     * added one result to it. An empty result slot means the network ran out: the rest of this recipe is left to the
     * chain (its next fill is refused, and the recipe is given up as at any table).
     */
    private static void ae2Clicks(Minecraft mc, Slot result) {
        if (ae2PutWait()) return;
        ItemStack carried = menu.getCarried();
        if (awaiting) {
            if (lastWasCraft) {
                if (ItemStack.matches(carried, ae2Carried)) {
                    if (++waited > TIMEOUT) fail("the terminal did not answer a craft");
                    return;
                }
                awaiting = false;
                boolean added = !carried.isEmpty() && (ae2Carried.isEmpty() || ItemStack.isSameItemSameTags(carried, ae2Carried))
                    && carried.getCount() - ae2Carried.getCount() == ae2Adds;
                if (!added) {
                    fail("a craft in the terminal did not put one result on the cursor");
                    return;
                }
                noteCrafted(1);
                clicksLeft--;
            } else {
                if (menu.getStateId() == clickState) {
                    if (++waited > TIMEOUT) fail("the server did not answer a click");
                    return;
                }
                awaiting = false;
            }
        }
        if (clicksLeft > 0 && !result.hasItem()) {
            NeiAddon.LOGGER.info("Autocrafting: the terminal's grid is not full again after a craft of {}; the network may be out of an ingredient", current);
            clicksLeft = 0;
        }
        if (clicksLeft <= 0) {
            if (!carried.isEmpty()) {
                deposit(mc, carried);
                return;
            }
            phase = Phase.NEXT;
            return;
        }
        ItemStack out = result.getItem();
        if (wrongResult(result)) return;
        boolean fits = carried.isEmpty()
            || ItemStack.isSameItemSameTags(carried, out) && carried.getCount() + out.getCount() <= carried.getMaxStackSize();
        if (!fits) {
            deposit(mc, carried);
            return;
        }
        ae2Carried = carried.copy();
        ae2Adds = out.getCount();
        if (!Ae2Crafting.craftOne(result)) {
            fail("AE2's craft action could not be sent");
            return;
        }
        awaiting = true;
        waited = 0;
        lastWasCraft = true;
    }

    /**
     * Puts the cursor's stack into the inventory: a stack of the same item with room for all of it, else an empty slot. At an
     * AE2 terminal what the chain makes on the way goes into the network first ({@link #ae2ToNetwork}), and anything with no
     * room in the inventory goes there too; only when neither takes it does the run stop.
     */
    private static void deposit(Minecraft mc, ItemStack carried) {
        if (ae2ToNetwork(carried) && ae2PutIntoNetwork(carried)) return;
        Slot target = inventorySlotFor(carried);
        if (target == null) {
            if (ae2 && ae2PutIntoNetwork(carried)) return;
            fail("no room in the inventory for what is on the cursor");
            return;
        }
        ae2Landed(carried);
        send(mc, target.index, false);
        lastWasCraft = false;
    }

    /**
     * AE2: true for a stack the chain made on the way (not an item asked for): it goes into the network, where AE2 refills
     * the grid from (it never refills from the player's inventory), instead of filling the inventory.
     */
    private static boolean ae2ToNetwork(ItemStack carried) {
        if (!ae2) return false;
        String id = plainId(RecipeData.idOf(carried));
        // by item, any subtype: when in doubt it is an item asked for and stays with the player
        return !ae2Refused.contains(id) && askedFor.stream().noneMatch(asked -> plainId(asked).equals(id));
    }

    /**
     * AE2: a stack goes into the inventory. When the network refused it {@link #AE2_PUT_TRIES} times, its item skips the
     * network for the rest of the run (no waiting on every later stack of it); the next stack starts the count again.
     */
    private static void ae2Landed(ItemStack carried) {
        if (ae2PutTries >= AE2_PUT_TRIES) ae2Refused.add(plainId(RecipeData.idOf(carried)));
        ae2PutTries = 0;
    }

    /**
     * AE2: sends the cursor's stack into the network (the terminal's own put, see {@link Ae2Crafting#putCarriedIntoNetwork});
     * {@link #ae2PutWait} then waits for the cursor to change. False when it cannot be sent, or the network refused this
     * stack {@link #AE2_PUT_TRIES} times in a row (see {@link #ae2Landed}).
     */
    private static boolean ae2PutIntoNetwork(ItemStack carried) {
        if (ae2PutTries >= AE2_PUT_TRIES) return false;
        ae2PutTries++;
        ae2Carried = carried.copy();
        if (!Ae2Crafting.putCarriedIntoNetwork(menu)) return false;
        NeiAddon.LOGGER.info("Autocrafting: putting {} into the AE2 network", carried.getHoverName().getString());
        ae2Putting = true;
        waited = 0;
        lastWasCraft = false;
        return true;
    }

    /**
     * AE2: while the cursor's stack goes into the network, waits for the cursor to change (the server answers it). True while
     * still waiting; when the cursor did not change in time it goes on, and the stack is tried again (or the run stops).
     */
    private static boolean ae2PutWait() {
        if (!ae2Putting) return false;
        ItemStack carried = menu.getCarried();
        if (ItemStack.matches(carried, ae2Carried) && ++waited <= TIMEOUT) return true;
        ae2Putting = false;
        waited = 0;
        // progress: the network took all of it, or part of it (the count on the cursor went down); the tries start again
        if (carried.isEmpty() || ItemStack.isSameItemSameTags(carried, ae2Carried) && carried.getCount() < ae2Carried.getCount()) ae2PutTries = 0;
        return false;
    }

    /** An inventory slot for the whole stack: a stack of the same item with room for all of it first, else an empty slot. */
    private static Slot inventorySlotFor(ItemStack carried) {
        Slot empty = null;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || !slot.mayPlace(carried)) continue;
            ItemStack in = slot.getItem();
            if (in.isEmpty()) {
                if (empty == null) empty = slot;
            } else if (ItemStack.isSameItemSameTags(in, carried) && in.getCount() + carried.getCount() <= Math.min(in.getMaxStackSize(), slot.getMaxStackSize(in))) {
                return slot;
            }
        }
        return empty;
    }

    /** Sends a click (pickup, or shift-click) on a slot of the menu and waits for the server's answer. */
    private static void send(Minecraft mc, int slot, boolean quickMove) {
        send(mc, slot, quickMove, 0);
    }

    /** A pickup click that does not wait for the server (filling the grid; the server's screen is fetched once at the end). */
    private static void clickNoWait(Minecraft mc, int slot, int button) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, ClickType.PICKUP, mc.player);
    }

    /** {@code button} 1 (right click) with an item on the cursor puts one of it into the slot. */
    private static void send(Minecraft mc, int slot, boolean quickMove, int button) {
        forceAnswer();
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, quickMove ? ClickType.QUICK_MOVE : ClickType.PICKUP, mc.player);
        awaiting = true;
        waited = 0;
    }

    /**
     * Makes sure the server answers the next click. This side guesses what a click does, and the server only sends back
     * what differs from that guess, so a click that was guessed right gets no answer at all. A click sent with a state id
     * the server does not have is answered with the menu's full contents (vanilla's handling of a stale click), so the
     * screen here is the server's again before anything is counted. The id is moved by two, so that the answer (the
     * server's id plus one) always differs from it.
     */
    private static void forceAnswer() {
        // far ahead: the server's own id may already be a few steps ahead of this side's (updates still on their way), and
        // a click whose id happens to be the server's is not answered when this side guessed it right
        for (int i = 0; i < 1000; i++) menu.incrementStateId();
        clickState = menu.getStateId();
    }

    private static void snapshot() {
        before.clear();
        for (Slot slot : gridSlots) before.put(slot, slot.getItem().copy());
    }

    /** Like {@link #craftsDone()}, but 0 when the grid did not change at all. */
    private static long craftsDoneExact() {
        long done = Long.MAX_VALUE;
        for (Map.Entry<Slot, ItemStack> e : before.entrySet()) {
            ItemStack was = e.getValue();
            // a tool is not used up (it wears, or breaks on its last craft): the other slots tell how many crafts were done
            if (was.isEmpty() || Tools.isTool(was)) continue;
            ItemStack now = e.getKey().getItem();
            long left = ItemStack.isSameItemSameTags(was, now) ? now.getCount() : 0;
            done = Math.min(done, was.getCount() - left);
        }
        return done == Long.MAX_VALUE ? 0 : Math.max(0, done);
    }

    /** AE2: the grid could not be put back into the network; says so, the items stay in the terminal's grid. */
    private static void ae2GridLeft() {
        NeiAddon.LOGGER.info("Autocrafting: the AE2 terminal's grid was not cleared");
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§6[NEI Features] §eAutocrafting left items in the terminal's crafting grid."));
    }

    private static void fail(String why) {
        sayStopped(why);
        startCleanup();
    }

    private static void sayStopped(String why) {
        NeiAddon.LOGGER.info("Autocrafting stopped while crafting {}: {}", current, why);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6[NEI Features] §eAutocrafting stopped: " + why + "."));
    }

    // ---------------------------------------------------------------- cleanup

    /** Ends autocrafting by putting the cursor and the grid back into the inventory, one checked click at a time. */
    private static void startCleanup() {
        if (walker == null) return;
        cleaned.clear();
        awaiting = false;
        phase = Phase.CLEANUP;
        waited = 0;
    }

    private static void cleanup(Minecraft mc) {
        if (ae2PutWait()) return;
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) stop();
                return;
            }
            awaiting = false;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            if (ae2ToNetwork(carried) && ae2PutIntoNetwork(carried)) return;
            Slot target = inventorySlotFor(carried);
            if (target == null) {
                if (ae2 && ae2PutIntoNetwork(carried)) return;
                NeiAddon.LOGGER.info("Autocrafting: no room in the inventory for what is on the cursor; it stays there");
                stop();
                return;
            }
            ae2Landed(carried);
            send(mc, target.index, false);
            return;
        }
        if (ae2) {
            // the grid goes back into the network (the terminal's clear action), then the end once it is empty
            boolean filled = gridSlots.stream()
                .anyMatch(Slot::hasItem);
            if (filled && !ae2Cleared) {
                ae2Cleared = true;
                waited = 0;
                if (!Ae2Crafting.clearGrid(menu)) {
                    ae2GridLeft();
                    stop();
                }
                return;
            }
            if (filled && ++waited <= TIMEOUT) return;
            if (filled) ae2GridLeft();
            afterCleanup();
            return;
        }
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && cleaned.add(slot)) {
                send(mc, slot.index, true);
                return;
            }
        }
        if (hasStorage) {
            phase = Phase.GIVE_BACK;
            noteMadeOnTheWay();
            reclaiming = false;
            returnClicks = 0;
            returnCount = -1;
            backTries = 0;
            pickedFrom = null;
            pickedUp = null;
            givenBack.clear();
            return;
        }
        afterCleanup();
    }

    /** After the cleanup (and the give-back): Restore Inventory Layout, or the end. */
    private static void afterCleanup() {
        if (!layout.isEmpty()) {
            phase = Phase.RESTORE;
            restoreNext = 0;
            restoreClicks = 0;
            pending.clear();
            return;
        }
        stop();
    }

    // ---------------------------------------------------------------- leftovers back into the container's storage

    /** Clicks the give-back may make: a bound, so it always ends. */
    private static final int RETURN_CLICKS = 200;
    /** Clicks putting the cursor back into the inventory before the give-back gives up on it. */
    private static final int BACK_TRIES = 3;
    /**
     * Per item: how many the open container's own storage held when autocrafting started (a crafting station's side
     * chest, a Sophisticated storage). Empty when the container has none.
     */
    private static final Map<String, Long> storageStart = new HashMap<>();
    /** Per item: how many the player's main inventory and hotbar held then (only when storageStart is not empty). */
    private static final Map<String, Long> playerStart = new HashMap<>();
    /** What each storage slot and main inventory slot held then (only when storageStart is not empty). */
    private static final Map<Slot, ItemStack> slotStart = new HashMap<>();
    /** Items the give-back is done with (all owed went back, or the storage took no more). */
    private static final java.util.Set<String> givenBack = new java.util.HashSet<>();
    /** The inventory slot the cursor's stack was picked up from, so the rest goes back there. */
    private static Slot pickedFrom;
    /** The item the last click picked up (null once the cursor holds it): a pickup that got nothing ends that item. */
    private static String pickedUp;
    /** Give-back clicks made, and the cursor's count when the last one into the storage was sent (-1: none since). */
    private static int returnClicks, returnCount = -1;
    /** Clicks made to put the cursor back into the inventory since it was last empty. */
    private static int backTries;
    /** Whether the snapshot was taken (once per start: a later sync, retrying a recipe, must not take it again). */
    private static boolean storageTaken;
    /** Whether the open container has storage slots of its own (set with the snapshot). */
    private static boolean hasStorage;
    /** The give-back's second pass: items the storage took from the player, and the asked-for items, go to the player. */
    private static boolean reclaiming;
    /**
     * The items this run was asked for: the results of the chain's top recipes (the ingredients of its made-up root
     * recipe), taken when the run starts. The chain's own list of plain items is no use here: each batch refills it with
     * whatever the inventory and storage hold.
     */
    private static final java.util.Set<String> askedFor = new java.util.HashSet<>();
    /** AE2: items (plain ids) the network refused this run; they go into the inventory. */
    private static final java.util.Set<String> ae2Refused = new java.util.HashSet<>();
    /** Per asked-for item: how many were asked for (the player keeps that many of what the inventory gained). */
    private static final Map<String, Long> askedAmount = new HashMap<>();
    /** Items this run crafted on the way to the asked-for items (set when the give-back starts). */
    private static final java.util.Set<String> madeOnTheWay = new java.util.HashSet<>();
    /** The storage slots the crafting took items from (set when the give-back starts): where items made on the way go. */
    private static final List<Slot> drawnFrom = new ArrayList<>();

    /**
     * A slot of the container's own storage: not the player's, the grid or the result. Only when the grid is known, so
     * that a grid slot is never taken for storage.
     */
    private static boolean storageSlot(Slot slot) {
        return !ae2 && !gridSlots.isEmpty() && !(slot.container instanceof Inventory) && !gridSlots.contains(slot) && !CraftSlots.isResult(slot);
    }

    /** Per item: what the storage slots (or the player's main inventory and hotbar) hold now. */
    private static Map<String, Long> counts(boolean storage) {
        Map<String, Long> counts = new HashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.hasItem() && (storage ? storageSlot(slot) : mainInventory(slot))) {
                counts.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
            }
        }
        return counts;
    }

    private static void clearStorage() {
        storageStart.clear();
        playerStart.clear();
        slotStart.clear();
        givenBack.clear();
        pickedFrom = null;
        pickedUp = null;
        returnClicks = 0;
        returnCount = -1;
        backTries = 0;
        storageTaken = false;
        hasStorage = false;
        reclaiming = false;
        madeOnTheWay.clear();
        drawnFrom.clear();
    }

    /** Taken once the screen is the server's (after the first sync), before the first transfer. */
    private static void snapshotStorage() {
        clearStorage();
        storageTaken = true;
        storageStart.putAll(counts(true));
        for (Slot slot : menu.slots) {
            if (storageSlot(slot)) hasStorage = true;
        }
        if (!hasStorage) return;
        playerStart.putAll(counts(false));
        for (Slot slot : menu.slots) {
            if (storageSlot(slot) || mainInventory(slot)) slotStart.put(slot, slot.getItem().copy());
        }
    }

    /**
     * How many of an item go back into the container's storage: what the storage gave up since autocrafting started, but
     * no more than the player's inventory (with the cursor) gained of it. Leftovers that the crafting or the cleanup put
     * into the player's inventory go back where they came from; the player's own items stay.
     */
    private static long owed(String id) {
        ItemStack carried = menu.getCarried();
        long now = counts(false).getOrDefault(id, 0L) + (!carried.isEmpty() && id.equals(RecipeData.idOf(carried)) ? carried.getCount() : 0);
        long gained = now - playerStart.getOrDefault(id, 0L);
        // the asked-for amount stays with the player, even where the storage also gave some of that item to the crafting;
        // only leftovers beyond it go back
        if (askedFor.contains(id)) gained -= askedAmount.getOrDefault(id, 0L);
        if (gained <= 0) return 0;
        // what was crafted on the way and is left over goes into the storage the crafting took from, all of it
        if (madeOnTheWay.contains(id)) return drawnFrom.isEmpty() ? 0 : gained;
        long lost = storageStart.getOrDefault(id, 0L) - counts(true).getOrDefault(id, 0L);
        return Math.max(0, Math.min(lost, gained));
    }

    /**
     * How many of an item go from the container's storage to the player (the give-back's second pass): what the storage
     * gained of it since autocrafting started (with the cursor, which holds what was picked up from the storage), but no
     * more than the player's inventory lost of it: the player's own items that the storage took (a tool the cleanup
     * shift-clicked out of the grid into a Sophisticated storage). The asked-for items go to the player, all the storage
     * gained of them.
     */
    private static long reclaim(String id) {
        ItemStack carried = menu.getCarried();
        long storageNow = counts(true).getOrDefault(id, 0L) + (!carried.isEmpty() && id.equals(RecipeData.idOf(carried)) ? carried.getCount() : 0);
        long gained = storageNow - storageStart.getOrDefault(id, 0L);
        if (gained <= 0) return 0;
        if (askedFor.contains(id)) return gained;
        long lost = playerStart.getOrDefault(id, 0L) - counts(false).getOrDefault(id, 0L);
        return Math.max(0, Math.min(lost, gained));
    }

    /** What the give-back still moves of an item in its current pass. */
    private static long toMove(String id) {
        return reclaiming ? reclaim(id) : owed(id);
    }

    /** Where the give-back puts a picked-up stack in its current pass: a storage slot, or (second pass) the player's. */
    private static Slot moveTarget(ItemStack stack) {
        return reclaiming ? playerSlotFor(stack) : storageSlotFor(stack);
    }

    /**
     * A main inventory slot for the stack, or null: a slot it was taken from (it held that item at the start and holds
     * fewer now), else a stack of the same item with room, else an empty slot.
     */
    private static Slot playerSlotFor(ItemStack stack) {
        String id = RecipeData.idOf(stack);
        for (Map.Entry<Slot, ItemStack> e : slotStart.entrySet()) {
            Slot slot = e.getKey();
            ItemStack was = e.getValue(), in = slot.getItem();
            if (was.isEmpty() || !inMenu(slot) || !mainInventory(slot) || !id.equals(RecipeData.idOf(was)) || !slot.mayPlace(stack)) continue;
            if (in.isEmpty() || in.getCount() < was.getCount() && hasRoom(slot, stack)) return slot;
        }
        Slot empty = null;
        for (Slot slot : menu.slots) {
            if (!mainInventory(slot) || !inMenu(slot) || !slot.mayPlace(stack)) continue;
            if (hasRoom(slot, stack)) return slot;
            if (!slot.hasItem() && empty == null) empty = slot;
        }
        return empty;
    }

    /** Second pass: where the rest of a stack picked up from the storage goes when its own slot does not take it. */
    private static Slot storageBackSlot(ItemStack stack) {
        for (Slot slot : menu.slots) {
            if (storageSlot(slot) && inMenu(slot) && (pickedFrom == null || sameKind(slot, List.of(pickedFrom))) && fits(slot, stack)) return slot;
        }
        return inventorySlotFor(stack);
    }

    /**
     * When the give-back starts: the items this run crafted on the way (results of its recipes that were crafted, not the
     * items asked for), and the storage slots the crafting took items from (they hold less of their item than at the start).
     */
    private static void noteMadeOnTheWay() {
        madeOnTheWay.clear();
        drawnFrom.clear();
        if (math != null) {
            for (ChainItem item : math.recipeResults) {
                if (item.recipeId != null && craftedBy.containsKey(item.recipeId) && !askedFor.contains(item.item)) madeOnTheWay.add(item.item);
            }
        }
        for (Map.Entry<Slot, ItemStack> e : slotStart.entrySet()) {
            Slot slot = e.getKey();
            ItemStack was = e.getValue(), in = slot.getItem();
            if (was.isEmpty() || !inMenu(slot) || !storageSlot(slot)) continue;
            if (in.isEmpty() || !RecipeData.idOf(in).equals(RecipeData.idOf(was)) || in.getCount() < was.getCount()) drawnFrom.add(slot);
        }
    }

    /** How many of its stack an inventory slot gained since autocrafting started. */
    private static long gainedIn(Slot slot) {
        ItemStack was = slotStart.getOrDefault(slot, ItemStack.EMPTY), now = slot.getItem();
        if (was.isEmpty() || !RecipeData.idOf(was).equals(RecipeData.idOf(now))) return now.getCount();
        return Math.max(0, now.getCount() - was.getCount());
    }

    /**
     * After the cleanup: moves leftovers the container's storage gave up from the player's inventory back into it, one
     * checked click at a time. A stack is picked up, as many as owed are put into the storage (the whole stack, or one
     * at a time), and the rest goes back where it was. An item whose click into the storage moved nothing is left alone.
     */
    private static void giveBack(Minecraft mc) {
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) stop();
                return;
            }
            awaiting = false;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            pickedUp = null;
            String id = RecipeData.idOf(carried);
            long owed = givenBack.contains(id) || backTries > 0 ? 0 : toMove(id);
            Slot into = owed > 0 && returnClicks < RETURN_CLICKS && carried.getCount() != returnCount ? moveTarget(carried) : null;
            if (into != null) {
                returnCount = carried.getCount();
                returnClicks++;
                send(mc, into.index, false, carried.getCount() <= owed ? 0 : 1);
                return;
            }
            // nothing more owed, or the storage takes no more: the rest goes back into the inventory (the slot it came
            // from first; a click there that left it on the cursor tries another slot, and a few such clicks give up)
            givenBack.add(id);
            returnCount = -1;
            if (++backTries > BACK_TRIES) {
                NeiAddon.LOGGER.info("Autocrafting: the cursor's stack could not be put back into the inventory; it stays there");
                stop();
                return;
            }
            Slot back = backTries == 1 && pickedFrom != null && fits(pickedFrom, carried) ? pickedFrom
                : reclaiming ? storageBackSlot(carried) : inventorySlotFor(carried);
            if (back == null) {
                NeiAddon.LOGGER.info("Autocrafting: no room in the inventory for what is on the cursor; it stays there");
                stop();
                return;
            }
            send(mc, back.index, false);
            return;
        }
        backTries = 0;
        pickedFrom = null;
        if (pickedUp != null) {
            // the pickup got nothing
            givenBack.add(pickedUp);
            pickedUp = null;
        }
        if (returnClicks < RETURN_CLICKS) {
            // a slot that gained the item first (leftovers, not the player's own stack), the smallest first (fewer clicks)
            Slot take = null;
            for (Slot slot : menu.slots) {
                if (!slot.hasItem() || !(reclaiming ? storageSlot(slot) && inMenu(slot) : mainInventory(slot)) || !slot.mayPickup(mc.player)) continue;
                ItemStack in = slot.getItem();
                String id = RecipeData.idOf(in);
                if (givenBack.contains(id)) continue;
                if (toMove(id) <= 0 || moveTarget(in) == null) {
                    givenBack.add(id);
                    continue;
                }
                if (take == null || better(slot, take)) take = slot;
            }
            if (take != null) {
                pickedFrom = take;
                pickedUp = RecipeData.idOf(take.getItem());
                returnCount = -1;
                returnClicks++;
                send(mc, take.index, false);
                return;
            }
        }
        if (!reclaiming) {
            // second pass, with its own click bound
            reclaiming = true;
            givenBack.clear();
            returnClicks = 0;
            returnCount = -1;
            return;
        }
        afterCleanup();
    }

    /** Is the slot a better one to take leftovers from than the other: it gained some and the other none, else smaller. */
    private static boolean better(Slot slot, Slot other) {
        boolean gained = gainedIn(slot) > 0, otherGained = gainedIn(other) > 0;
        if (gained != otherGained) return gained;
        return slot.getItem().getCount() < other.getItem().getCount();
    }

    /** Can the slot take the whole stack (empty, or the same item with room)? */
    private static boolean fits(Slot slot, ItemStack stack) {
        if (!slot.mayPlace(stack)) return false;
        ItemStack in = slot.getItem();
        return in.isEmpty() || ItemStack.isSameItemSameTags(in, stack) && in.getCount() + stack.getCount() <= Math.min(in.getMaxStackSize(), slot.getMaxStackSize(in));
    }

    /**
     * A storage slot for the stack, or null: a slot its item was taken from (it held that item at the start and holds
     * fewer now), else a stack of the same item with room, else an empty slot; the last two only of the same kind as a
     * slot the item was taken from (the same class on the same container or item handler). So a slot of another kind (a filter slot,
     * another mod's ghost slot) is not used, and the item still goes back when something else filled the slot it came
     * from (crafted items that the storage itself took).
     */
    private static Slot storageSlotFor(ItemStack stack) {
        String id = RecipeData.idOf(stack);
        List<Slot> sources = new ArrayList<>();
        for (Map.Entry<Slot, ItemStack> e : slotStart.entrySet()) {
            Slot slot = e.getKey();
            ItemStack was = e.getValue(), in = slot.getItem();
            if (was.isEmpty() || !inMenu(slot) || !storageSlot(slot) || !id.equals(RecipeData.idOf(was))) continue;
            sources.add(slot);
            if (!slot.mayPlace(stack)) continue;
            if (in.isEmpty()) return slot;
            if (in.getCount() < was.getCount() && hasRoom(slot, stack)) return slot;
        }
        // an item made on the way was never in the storage: it goes beside what the crafting took
        if (sources.isEmpty() && madeOnTheWay.contains(id)) sources = drawnFrom;
        if (sources.isEmpty()) return null;
        Slot empty = null;
        for (Slot slot : menu.slots) {
            if (!storageSlot(slot) || !sameKind(slot, sources) || !slot.mayPlace(stack)) continue;
            if (!slot.hasItem()) {
                if (empty == null) empty = slot;
            } else if (hasRoom(slot, stack)) {
                return slot;
            }
        }
        return empty;
    }

    /**
     * Is the slot of the same class, on the same container and (for the loaders' item handler slots, which all share one
     * empty container) on the same item handler as one of the others?
     */
    private static boolean sameKind(Slot slot, List<Slot> others) {
        for (Slot other : others) {
            if (other.getClass() == slot.getClass() && other.container == slot.container && CraftSlots.sameHandler(slot, other)) return true;
        }
        return false;
    }

    /** Is the slot still this index's slot of the menu (a click goes by its index)? */
    private static boolean inMenu(Slot slot) {
        return slot.index >= 0 && slot.index < menu.slots.size() && menu.slots.get(slot.index) == slot;
    }

    /** Does the slot hold the stack's item (same tags) with room for at least one more? */
    private static boolean hasRoom(Slot slot, ItemStack stack) {
        ItemStack in = slot.getItem();
        return ItemStack.isSameItemSameTags(in, stack) && in.getCount() < Math.min(in.getMaxStackSize(), slot.getMaxStackSize(in));
    }

    // ---------------------------------------------------------------- Restore Inventory Layout

    /** Clicks the layout restore may make in all (a swap is two or three): a bound, so it always ends. */
    private static final int RESTORE_CLICKS = 120;
    /** The main inventory and hotbar when autocrafting started, by inventory index; empty when the setting is off. */
    private static final Map<Integer, ItemStack> layout = new HashMap<>();
    /** The clicks of the swap in progress. */
    private static final java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();
    private static int restoreNext, restoreClicks;

    private static boolean mainInventory(Slot slot) {
        return slot.container instanceof Inventory && slot.getContainerSlot() < 36;
    }

    private static void snapshotLayout() {
        layout.clear();
        if (!AddonConfig.AUTOCRAFTING_RESTORE.get()) return;
        for (Slot slot : menu.slots) {
            if (mainInventory(slot)) layout.put(slot.getContainerSlot(), slot.getItem().copy());
        }
    }

    /**
     * Puts items back where they were before autocrafting, one checked click at a time: for each inventory slot (in order)
     * that held an item which is now elsewhere, the item is picked up from a slot that is not itself already right, put
     * into its old slot, and whatever was there goes into the slot it came from. Slots whose old item is gone stay as
     * they are, and so do the crafted items (in slots that were empty, or wherever they landed). Anything unexpected (the
     * cursor not empty between swaps, no answer) ends it.
     */
    private static void restore(Minecraft mc) {
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) {
                    NeiAddon.LOGGER.info("Autocrafting: the server did not answer while restoring the inventory layout");
                    stop();
                }
                return;
            }
            awaiting = false;
        }
        if (!pending.isEmpty()) {
            send(mc, pending.poll(), false);
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            // a swap did not end with an empty cursor: put it away and stop restoring
            NeiAddon.LOGGER.info("Autocrafting: restoring the inventory layout left something on the cursor; stopping there");
            layout.clear();
            startCleanup();
            return;
        }
        while (restoreNext < 36 && restoreClicks < RESTORE_CLICKS) {
            int i = restoreNext++;
            ItemStack want = layout.get(i);
            Slot to = null;
            for (Slot slot : menu.slots) {
                if (mainInventory(slot) && slot.getContainerSlot() == i) to = slot;
            }
            if (want == null || want.isEmpty() || to == null || ItemStack.isSameItemSameTags(to.getItem(), want)) continue;
            Slot from = null;
            for (Slot slot : menu.slots) {
                if (slot == to || !mainInventory(slot) || !ItemStack.isSameItemSameTags(slot.getItem(), want)) continue;
                ItemStack itsOwn = layout.get(slot.getContainerSlot());
                if (itsOwn != null && ItemStack.isSameItemSameTags(slot.getItem(), itsOwn)) continue; // already where it belongs
                from = slot;
                break;
            }
            if (from == null) continue;
            pending.add(from.index);
            pending.add(to.index);
            if (to.hasItem()) pending.add(from.index);
            restoreClicks += pending.size();
            send(mc, pending.poll(), false);
            return;
        }
        NeiAddon.LOGGER.info("Autocrafting: inventory layout restored ({} clicks)", restoreClicks);
        stop();
    }

    // ---------------------------------------------------------------- recipes with a tool: several sets around one tool

    /** Most clicks one top-up may take: a bound, so it always ends. */
    private static final int TOPUP_CLICKS = 600;
    /** The grid slots to fill, in order, and how many items each should hold. */
    private static final List<Slot> topSlots = new ArrayList<>();
    private static final Map<Slot, Integer> topTarget = new HashMap<>();
    private static int topIndex;
    /** Where the stack on the cursor was picked up from, to put the rest back there. */
    private static Slot topSource;

    private static boolean mainSlot(Slot slot) {
        return slot.container instanceof Inventory && slot.getContainerSlot() < 36 && !gridSlots.contains(slot);
    }

    /**
     * A slot the top-up may take items from: the player's main inventory and hotbar, or the container's own storage (a
     * crafting station's side chest, a Sophisticated storage), so ingredients kept there fill the grid too.
     */
    private static boolean topSourceSlot(Slot slot) {
        return mainSlot(slot) || storageSlot(slot) && slot.hasItem() && slot.mayPickup(Minecraft.getInstance().player);
    }

    /**
     * JEI only moves one set into the grid when the recipe has a tool (an item of one per stack), so a recipe with a
     * hammer or a file would craft one at a time. Here the other slots get more of their items from the inventory and
     * the container's own storage (whole or half stacks where they fit, single items for the rest), so the grid holds
     * exactly as many sets as are wanted (or as the tool and the items allow) and one shift-click crafts them all. False
     * when there is no tool in the grid or not enough for two sets.
     */
    private static boolean startTopUp() {
        long sets = walker.remaining();
        boolean tool = false;
        Map<Slot, Integer> perSet = new HashMap<>();
        for (Slot slot : gridSlots) {
            ItemStack in = slot.getItem();
            if (in.isEmpty()) continue;
            if (Tools.isTool(in)) {
                tool = true;
                sets = Math.min(sets, Tools.craftsLeft(in));
            } else {
                perSet.put(slot, in.getCount());
                sets = Math.min(sets, Math.min(in.getMaxStackSize(), slot.getMaxStackSize(in)) / in.getCount());
            }
        }
        if (!tool || perSet.isEmpty()) return false;
        // what the inventory has of each grid item, shared between the grid slots that want it
        for (Map.Entry<Slot, Integer> e : perSet.entrySet()) {
            ItemStack in = e.getKey().getItem();
            long have = 0;
            int wanted = 0;
            for (Slot slot : menu.slots) {
                if (topSourceSlot(slot) && ItemStack.isSameItemSameTags(slot.getItem(), in)) have += slot.getItem().getCount();
            }
            for (Map.Entry<Slot, Integer> o : perSet.entrySet()) {
                if (ItemStack.isSameItemSameTags(o.getKey().getItem(), in)) wanted += o.getValue();
            }
            sets = Math.min(sets, 1 + have / Math.max(1, wanted));
        }
        if (sets < 2) return false;
        topSlots.clear();
        topTarget.clear();
        for (Slot slot : gridSlots) {
            Integer per = perSet.get(slot);
            if (per == null) continue;
            topSlots.add(slot);
            topTarget.put(slot, (int) (per * sets));
        }
        topIndex = 0;
        topSource = null;
        topSynced = false;
        awaiting = false;
        waited = 0;
        phase = Phase.TOPUP;
        NeiAddon.LOGGER.info("Autocrafting: {} has a tool, filling the grid for {} crafts", current, sets);
        return true;
    }

    private static void topUp(Minecraft mc, Slot result) {
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) fail("the server did not answer while filling the grid");
                return;
            }
            awaiting = false;
        }
        if (topSynced) {
            // the screen is the server's again: craft what the grid really holds (counted from it, see craftsDone)
            topSynced = false;
            topSlots.clear();
            topTarget.clear();
            if (!menu.getCarried()
                .isEmpty()) {
                fail("something was left on the cursor after filling the grid");
                return;
            }
            if (!result.hasItem() || gridSets() < 1) {
                fail("the grid is not a full set after filling it");
                return;
            }
            if (wrongResult(result)) return;
            craft(mc, result);
            return;
        }
        // every click at once: this side applies each one right away, and they are plain moves the server does the same way
        for (int clicks = 0;; clicks++) {
            if (clicks >= TOPUP_CLICKS) {
                fail("filling the grid took too many clicks");
                return;
            }
            ItemStack carried = menu.getCarried();
            while (topIndex < topSlots.size() && topSlots.get(topIndex)
                .getItem()
                .getCount() >= topTarget.get(topSlots.get(topIndex))) topIndex++;
            int slotId;
            int button;
            if (topIndex >= topSlots.size()) {
                if (carried.isEmpty()) break;
                Slot back = putBackSlot(carried);
                if (back == null) {
                    fail("no room in the inventory for what is on the cursor");
                    return;
                }
                slotId = back.index;
                button = 0;
            } else {
                Slot slot = topSlots.get(topIndex);
                ItemStack in = slot.getItem();
                if (in.isEmpty()) {
                    fail("a grid slot emptied while the grid was being filled");
                    return;
                }
                int need = topTarget.get(slot) - in.getCount();
                if (!carried.isEmpty() && ItemStack.isSameItemSameTags(carried, in)) {
                    // all of the cursor when the slot still wants that many (left click), else one (right click)
                    slotId = slot.index;
                    button = carried.getCount() <= need ? 0 : 1;
                } else if (!carried.isEmpty()) {
                    Slot back = putBackSlot(carried);
                    if (back == null) {
                        fail("no room in the inventory for what is on the cursor");
                        return;
                    }
                    slotId = back.index;
                    button = 0;
                } else {
                    // the player's inventory first, then the container's own storage
                    Slot source = null;
                    for (Slot s : menu.slots) {
                        if (mainSlot(s) && ItemStack.isSameItemSameTags(s.getItem(), in)) {
                            source = s;
                            break;
                        }
                    }
                    for (Slot s : menu.slots) {
                        if (source != null) break;
                        if (topSourceSlot(s) && ItemStack.isSameItemSameTags(s.getItem(), in)) source = s;
                    }
                    if (source == null) {
                        // the inventory ran out: craft with what is in
                        topIndex = topSlots.size();
                        continue;
                    }
                    topSource = source;
                    // the whole stack when the slot wants all of it, else half of it (right click)
                    slotId = source.index;
                    button = source.getItem()
                        .getCount() <= need ? 0 : 1;
                }
            }
            int before = carried.getCount();
            clickNoWait(mc, slotId, button);
            if (menu.getCarried()
                .getCount() == before && ItemStack.isSameItemSameTags(menu.getCarried(), carried)) {
                fail("a click while filling the grid did nothing");
                return;
            }
        }
        // one empty click outside the slots with a state id the server does not have: it answers with the whole screen
        topSynced = true;
        forceAnswer();
        clickNoWait(mc, -999, 0);
        awaiting = true;
        waited = 0;
        lastWasCraft = false;
    }

    private static boolean topSynced;

    /**
     * The rest of a picked-up stack goes back where it came from (it is empty, or holds the same item with room for all
     * of it: a storage slot can hold more than one stack), else into any inventory slot that takes it.
     */
    private static Slot putBackSlot(ItemStack carried) {
        if (topSource != null && topSource.mayPlace(carried)) {
            ItemStack in = topSource.getItem();
            if (in.isEmpty() || ItemStack.isSameItemSameTags(in, carried) && in.getCount() + carried.getCount() <= topSource.getMaxStackSize(in)) return topSource;
        }
        return inventorySlotFor(carried);
    }

    /** The result of a craft stayed in its slot: says why (in the log, and in chat for the player) as far as it can tell. */
    private static void stoppedOnResult(Minecraft mc, Slot result) {
        ItemStack out = result.getItem();
        String what = out.isEmpty() ? "the result" : out.getHoverName()
            .getString();
        String why;
        if (menu.getStateId() == clickState) why = "the server did not answer the craft";
        else if (!out.isEmpty() && !roomFor(out)) why = "no room in the inventory for " + what;
        else why = what + " could not be taken out of the result slot, although the inventory has room";
        StringBuilder grid = new StringBuilder();
        for (Slot slot : gridSlots) {
            ItemStack in = slot.getItem();
            if (!in.isEmpty()) grid.append(' ')
                .append(in.getCount())
                .append('x')
                .append(RecipeData.idOf(in))
                .append(Tools.isTool(in) ? "(tool)" : "");
        }
        NeiAddon.LOGGER.info("Autocrafting stopped while crafting {}: {} (grid:{})", current, why, grid);
        if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6[NEI Features] §eAutocrafting stopped: " + why + "."));
    }

    /** Is there an inventory slot (main inventory or hotbar) that takes all of the stack? Shift-clicking a result goes there. */
    private static boolean roomFor(ItemStack stack) {
        int left = stack.getCount();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= 36 || !slot.mayPlace(stack)) continue;
            ItemStack in = slot.getItem();
            int max = Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack));
            if (in.isEmpty()) left -= max;
            else if (ItemStack.isSameItemSameTags(in, stack)) left -= max - in.getCount();
            if (left <= 0) return true;
        }
        return false;
    }

    /** Ticks a first transfer may take to show a result before the screen is fetched again and it is retried. */
    private static final int FIRST_RESULT_TICKS = 10;
    /** Ticks to wait after the server's answer to a sync click, see the SYNC phase. */
    private static final int SETTLE_TICKS = 2;
    private static int settle;

    /** Per recipe: whether it could be crafted the last time the walker asked. */
    private static final Map<String, Boolean> lastCan = new java.util.LinkedHashMap<>();

    /**
     * The chain is done as far as it goes: recipes that still could not be crafted at the end are named in chat with what
     * they lack (JEI's own check against the inventory now), so the player knows why the end result did not get made.
     */
    private static void reportUnfinished(Minecraft mc) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : lastCan.entrySet()) {
            // recipes the chain wanted that never got crafted in this run (in the order they were tried: end results first)
            if (e.getValue() || parts.size() >= 3 || craftedBy.containsKey(e.getKey()) || skipped.containsKey(e.getKey())) continue;
            RecipeLookup.Target<?> target = target(e.getKey());
            String missing = ae2 ? ae2Missing(e.getKey()) : null;
            if (ae2 && missing == null) missing = ae2MissingTool(e.getKey());
            String why = missing != null ? missing + " is not in the inventory, the network or the grid"
                : target == null ? "recipe not found" : target.whyNot(menu, mc.player);
            if (why == null) continue;
            parts.add(recipeName(e.getKey()) + " (" + why + ")");
        }
        NeiAddon.LOGGER.info("Autocrafting finished{}", parts.isEmpty() ? "" : "; could not craft " + String.join("; ", parts));
        if (!parts.isEmpty() && mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component
            .literal("§6[NEI Features] §eAutocrafting finished, but could not craft " + String.join("; ", parts) + "."));
        saySkipped(mc);
    }

    /**
     * AE2: the chat line naming the recipes the run skipped, sent once per run: at the normal end, or when the run stops
     * (see {@link #stop}), whichever comes first.
     */
    private static void saySkipped(Minecraft mc) {
        if (skippedSaid || skipped.isEmpty()) return;
        skippedSaid = true;
        if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component
            .literal("§6[NEI Features] §eAutocrafting skipped these recipes: " + String.join(" | ", skipped.values()) + "."));
    }

    /** Crafts done per recipe in this run. */
    private static final Map<String, Long> craftedBy = new HashMap<>();

    private static void noteCrafted(long n) {
        walker.crafted(n);
        craftedBy.merge(current, n, Long::sum);
    }

    private static String recipeName(String recipeId) {
        for (ChainItem item : math.recipeResults) {
            if (recipeId.equals(item.recipeId)) return FluidStacks.name(FluidStacks.stackOf(item.item))
                .getString();
        }
        return recipeId;
    }

    /** Recipes whose result is taken by single clicks because shift-clicking it did nothing. */
    private static final java.util.Set<String> singleClicks = new java.util.HashSet<>();

    /** Per recipe: how often the screen was fetched again after a transfer that did nothing. */
    private static final Map<String, Integer> syncRetries = new HashMap<>();

    /**
     * A click that changes nothing (a shift-click on an empty slot of the inventory or the grid) sent with a state id the
     * server does not have: the server answers with the whole screen, so this side's view is the server's again. False
     * when no slot is empty.
     */
    private static boolean syncClick(Minecraft mc) {
        settle = 0;
        for (Slot slot : menu.slots) {
            if (slot.hasItem() || !(gridSlots.contains(slot) || slot.container instanceof Inventory && slot.getContainerSlot() < 36)) continue;
            forceAnswer();
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
            awaiting = true;
            waited = 0;
            lastWasCraft = false;
            return true;
        }
        return false;
    }
}
