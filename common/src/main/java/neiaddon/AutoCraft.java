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
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ClientTickEvent;

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
        RESTORE
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
    /** Grid slots the cleanup already shift-clicked (a slot that does not empty is not tried again). */
    private static final java.util.Set<Slot> cleaned = new java.util.HashSet<>();

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
        for (Slot slot : m.slots) {
            if (slot instanceof ResultSlot) return slot;
        }
        return null;
    }

    /** What the player can craft with: every slot of the open menu except the result. NEI's getInventoryItems. */
    private static Map<String, Long> inventory() {
        Map<String, Long> items = new HashMap<>();
        Player player = Minecraft.getInstance().player;
        for (Slot slot : menu.slots) {
            if (slot.hasItem() && !(slot instanceof ResultSlot) && slot.mayPickup(player)) {
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
        stop();
        menu = open;
        gridSlots = craftingGrid(open);
        snapshotLayout();
        fullSets.clear();
        math = chain;
        math.createMasterRoot();
        if (ignoreInventory) ignoreInventory();
        math.createMasterRoot();
        walker = new ChainAutocraft(math, new ChainAutocraft.Host() {

            @Override
            public Map<String, Long> inventory() {
                return AutoCraft.inventory();
            }

            @Override
            public boolean canCraft(String recipeId) {
                RecipeLookup.Target<?> target = target(recipeId);
                boolean can = target != null && target.canTransfer(menu, Minecraft.getInstance().player);
                if (!can) NeiAddon.LOGGER.info("Autocrafting: cannot craft {} now (recipe found: {})", recipeId, target != null);
                return can;
            }
        });
        phase = Phase.NEXT;
        NeiAddon.LOGGER.info("Autocrafting started: {}", math.outputRecipes);
        return true;
    }

    public static void stop() {
        gridSlots = List.of();
        layout.clear();
        pending.clear();
        before.clear();
        cleaned.clear();
        awaiting = false;
        walker = null;
        math = null;
        menu = null;
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
    static void onTick(ClientTickEvent.Post event) {
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
                    String recipeId = walker.next();
                    if (recipeId == null) {
                        // done: whatever is still on the cursor or in the grid goes back into the inventory
                        startCleanup();
                        return;
                    }
                    RecipeLookup.Target<?> target = target(recipeId);
                    // batching: as many sets as the grid takes while more crafts are wanted than the last full grid of this
                    // recipe held (unknown at first: try); one set otherwise
                    full = !gridSlots.isEmpty() && walker.remaining() > 1
                        && (AddonConfig.AUTOCRAFTING_FAST.get() || walker.remaining() >= fullSets.getOrDefault(recipeId, 2L));
                    if (target == null || !target.transfer(menu, mc.player, full)) {
                        NeiAddon.LOGGER.info("Autocrafting: transfer of {} was refused", recipeId);
                        walker.abandon();
                        return;
                    }
                    current = recipeId;
                    phase = Phase.WAIT_RESULT;
                    waited = 0;
                }
                case WAIT_RESULT -> {
                    if (result.hasItem()) {
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
                    } else if (++waited > TIMEOUT) {
                        NeiAddon.LOGGER.info("Autocrafting: no result after the transfer, giving up on this recipe");
                        walker.abandon();
                        phase = Phase.NEXT;
                    }
                }
                case WAIT_ONE_SET -> {
                    if (result.hasItem() && gridSets() == 1) {
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
                        walker.crafted(craftsDone());
                        phase = Phase.NEXT;
                    } else if (++waited > TIMEOUT) {
                        NeiAddon.LOGGER.info("Autocrafting stopped: the result did not move (inventory full?)");
                        startCleanup();
                    }
                }
                case CLICKS -> clicks(mc, result);
                case CLEANUP -> cleanup(mc);
                case RESTORE -> restore(mc);
            }
            if (was != Phase.WAIT_CLEAR || phase != Phase.NEXT) break;
        }
    }

    /** Shift-clicks the result: vanilla crafts as long as the grid has a full set (and the inventory has room). */
    private static void craft(Minecraft mc, Slot result) {
        before.clear();
        for (Slot slot : gridSlots) before.put(slot, slot.getItem().copy());
        forceAnswer();
        mc.gameMode.handleContainerInput(menu.containerId, result.index, 0, ContainerInput.QUICK_MOVE, mc.player);
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
            if (was.isEmpty()) continue;
            ItemStack now = e.getKey().getItem();
            long left = ItemStack.isSameItemSameComponents(was, now) ? now.getCount() : 0;
            done = Math.min(done, was.getCount() - left);
        }
        return done == Long.MAX_VALUE || done < 1 ? 1 : done;
    }

    /** How many full sets the crafting grid holds: the smallest stack among its filled slots. */
    private static long gridSets() {
        long sets = Long.MAX_VALUE;
        for (Slot slot : gridSlots) {
            if (slot.hasItem()) sets = Math.min(sets, slot.getItem().getCount());
        }
        return sets == Long.MAX_VALUE ? 0 : sets;
    }

    /** The slots of the menu's crafting grid (the container behind the slots of a crafting grid), or none if unknown. */
    private static List<Slot> craftingGrid(AbstractContainerMenu m) {
        List<Slot> grid = new ArrayList<>();
        for (Slot slot : m.slots) {
            if (slot.container instanceof CraftingContainer) grid.add(slot);
        }
        return grid;
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
                walker.crafted(1);
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
        boolean fits = carried.isEmpty()
            || ItemStack.isSameItemSameComponents(carried, out) && carried.getCount() + out.getCount() <= carried.getMaxStackSize();
        if (!fits) {
            deposit(mc, carried);
            return;
        }
        snapshot();
        send(mc, result.index, false);
        lastWasCraft = true;
    }

    /** Puts the cursor's stack into the inventory: an empty slot, else a stack of the same item with room for all of it. */
    private static void deposit(Minecraft mc, ItemStack carried) {
        Slot target = inventorySlotFor(carried);
        if (target == null) {
            fail("no room in the inventory for what is on the cursor");
            return;
        }
        send(mc, target.index, false);
        lastWasCraft = false;
    }

    private static Slot inventorySlotFor(ItemStack carried) {
        Slot same = null;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || !slot.mayPlace(carried)) continue;
            ItemStack in = slot.getItem();
            if (in.isEmpty()) return slot;
            if (same == null && ItemStack.isSameItemSameComponents(in, carried) && in.getCount() + carried.getCount() <= Math.min(in.getMaxStackSize(), slot.getMaxStackSize(in))) same = slot;
        }
        return same;
    }

    /** Sends a click (pickup, or shift-click) on a slot of the menu and waits for the server's answer. */
    private static void send(Minecraft mc, int slot, boolean quickMove) {
        forceAnswer();
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, quickMove ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
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
        menu.incrementStateId();
        menu.incrementStateId();
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
            if (was.isEmpty()) continue;
            ItemStack now = e.getKey().getItem();
            long left = ItemStack.isSameItemSameComponents(was, now) ? now.getCount() : 0;
            done = Math.min(done, was.getCount() - left);
        }
        return done == Long.MAX_VALUE ? 0 : Math.max(0, done);
    }

    private static void fail(String why) {
        NeiAddon.LOGGER.info("Autocrafting stopped: {}", why);
        startCleanup();
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
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) stop();
                return;
            }
            awaiting = false;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            Slot target = inventorySlotFor(carried);
            if (target == null) {
                NeiAddon.LOGGER.info("Autocrafting: no room in the inventory for what is on the cursor; it stays there");
                stop();
                return;
            }
            send(mc, target.index, false);
            return;
        }
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && cleaned.add(slot)) {
                send(mc, slot.index, true);
                return;
            }
        }
        if (!layout.isEmpty()) {
            phase = Phase.RESTORE;
            restoreNext = 0;
            restoreClicks = 0;
            pending.clear();
            return;
        }
        stop();
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
            if (want == null || want.isEmpty() || to == null || ItemStack.isSameItemSameComponents(to.getItem(), want)) continue;
            Slot from = null;
            for (Slot slot : menu.slots) {
                if (slot == to || !mainInventory(slot) || !ItemStack.isSameItemSameComponents(slot.getItem(), want)) continue;
                ItemStack itsOwn = layout.get(slot.getContainerSlot());
                if (itsOwn != null && ItemStack.isSameItemSameComponents(slot.getItem(), itsOwn)) continue; // already where it belongs
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
}
