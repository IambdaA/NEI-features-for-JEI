package neiaddon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import neiaddon.chain.ChainAutocraft;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainMath;
import neiaddon.chain.CraftCount;
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
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
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
 * one craft of a recipe is wanted the grid is filled with as many sets as fit, and every craft of that batch is
 * one click on the result that the server answers and vouches for (#46); when a full grid
 * would make more than is wanted, the rest of that recipe is crafted one set at a time. It stops when the screen
 * closes or another container opens, or when the result cannot be moved (inventory full).
 */
public final class AutoCraft {

    /** Ticks to wait for the server before giving up on a craft. */
    private static final int TIMEOUT = 40;

    private enum Phase {
        NEXT,
        GRID_CLEAR,
        TRIM,
        WAIT_RESULT,
        WAIT_ONE_SET,
        WAIT_CLEAR,
        CLICKS,
        CLEANUP,
        GIVE_BACK,
        RESTORE,
        TOPUP,
        SYNC,
        /** The recipe's tool is added to the grid on the screen the fill's sync just fetched. */
        ADD_TOOL,
        RECHECK,
        AE2_CLEAR
    }

    /** What {@link #addMissingTool} did about the recipe's tools. */
    private enum ToolFill {
        /** Nothing was missing: the recipe wants no tool, or the grid already holds each one. */
        NOT_NEEDED,
        /** Every missing tool was clicked into the grid (optimistic clicks: the screen must be synced again). */
        PLACED,
        /** A tool is in neither the grid nor any reachable slot; no click was sent. */
        REFUSED,
        /** A tool is in neither the grid nor any reachable slot, after some tools were already clicked in. */
        REFUSED_AFTER_CLICKS
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
    /**
     * The recipes whose grid was already fetched from the server before their first batch craft in this run: the
     * batch's sets, its clicks and their credit all come from that screen (see the {@link Phase#WAIT_RESULT} case).
     * The sync is once per recipe BATCH, even though the batch itself crafts in checked steps, each credited
     * from the server's answer.
     */
    private static final java.util.Set<String> batchSynced = new java.util.HashSet<>();
    /**
     * Where the {@link Phase#SYNC} case goes on once the server's screen has arrived: {@link Phase#NEXT} (the transfer
     * is issued again), or back to {@link Phase#WAIT_RESULT} when the sync runs before the first craft of a batch, so
     * its sets and its craft count are read from the server's own screen, or to {@link Phase#ADD_TOOL} after the fill
     * of a recipe with a tool ingredient, so the tool goes into the server's grid.
     */
    private static Phase resumeAfterSync = Phase.NEXT;
    /**
     * Where the {@link Phase#GRID_CLEAR} case goes on once the grid holds nothing this side can move out:
     * {@link Phase#NEXT} (the next transfer then starts from an empty grid), or {@link Phase#TRIM} when the grid is
     * emptied so the tail of the current recipe can be re-filled with one set.
     */
    private static Phase resumeAfterFill = Phase.NEXT;
    /**
     * Grid slots the {@link Phase#GRID_CLEAR} case already shift-clicked (a slot that does not empty, an inventory
     * without room, is not tried again).
     */
    private static final java.util.Set<Slot> fillCleared = new java.util.HashSet<>();
    /**
     * How many {@link Phase#GRID_CLEAR} rounds in a row found the grid still filled after trying every filled slot once
     * (an inventory without room). After {@link #GRID_CLEAR_ROUNDS} the fill is issued on top of the items still in the
     * grid (bounded, so a grid that cannot be emptied cannot loop); it resets when a round empties the grid.
     */
    private static int fillClearTries;
    private static final int GRID_CLEAR_ROUNDS = 2;
    /**
     * Whether the plan dump (see the {@link Phase#NEXT} case) was already written this run. The maths only knows the
     * plan after the first {@code walker.next()} refreshes it, so the dump runs there, once.
     */
    private static boolean planDumped;
    /**
     * Per recipe, how often in a row its batch shift-click crafted nothing on the server (see
     * {@link #craftsDoneBatch()}); at {@link CraftCount#BATCH_ZERO_TRIES} the recipe is given up on instead of retried
     * forever ({@link CraftCount#giveUp(int)}).
     */
    private static final Map<String, Integer> batchZeroCrafts = new HashMap<>();
    private static List<Slot> gridSlots = List.of();
    private static final Map<Slot, ItemStack> before = new HashMap<>();
    /** The menu's state id sent with our last click; the server's answer replaces it (see forceAnswer). */
    private static int clickState;
    /** Faster Autocrafting: crafts still to make by clicking the result, and whether a click waits for the server. */
    private static long clicksLeft;
    /** What one batch of result clicks has crafted so far, for the count line in the log. */
    private static long clicksMade;
    /** Whether this batch's grid held a tool: once the tool breaks its slot is empty, so the click
     *  loop cannot tell a wear-out from an unmeasurable grid without remembering that the grid had a tool. */
    private static boolean hadTool;
    /**
     * How many result clicks one step of the click loop sends at once. They are shift-clicks, so the crafted items go
     * straight into the player's inventory (see {@link #clicks}). Recipes the WAIT_CLEAR recovery pinned in
     * {@link #singleClicks} never burst: they always click once onto the cursor. A burst is what a player's click-spam
     * on a crafting table sends. Every vanilla click first crafts on this side's model of the screen (the vanilla
     * shift-click simulation ends in the same {@code ResultSlot} consumption code the server runs for its copy of the
     * click), and then sends that guess to the server, which processes every click it gets in order.
     * {@link #forceAnswer()} makes each click of the burst carry a stale state id, so the server answers each one with
     * a full copy of the screen (a stale id is only a flag there, never a refusal). The burst is measured from the grid
     * after the answers: it must show exactly as many sets lost as the burst sent. A credit that measures short waits
     * for the answers to stop coming and measures the same grid again before it is believed (see {@link #settling}). 4
     * is also what GTNH's own NEI throttle branch batches its crafts by.
     */
    private static final int CLICK_BURST = 4;
    /** The clicks the outstanding burst sent; its answer must show exactly that many
     *  grid sets consumed, and when the grid shows fewer only those are credited. */
    private static int clicksSent;
    /** Whether the credit window now open was opened by the one shift-click step: vanilla's repeat loop inside that
     *  click crafts up to the whole grid in one go, and the first answer that advances the state id can lag the
     *  craft's own per-slot changes. Without a re-measure, a lagging answer would credit 1..6 of grids holding 32..64
     *  whole sets that the click had already consumed, so only this kind of step re-measures the grid once it goes
     *  quiet before believing it crafted less than the snapshot's sets. */
    private static boolean shiftStep;
    /** Whether this visit of the click loop may still click in a burst: a burst that
     *  consumed less than it sent falls back to one click per step for the rest of the visit. */
    private static boolean burstHere = true;
    /** The answers to a burst come back one per click. They usually all land in one client tick, but a server tick can
     *  split them over two. A burst that measured short may therefore be only the late answers. The credit does not
     *  take the short count then, because the server already crafted the rest and the walker would craft those sets a
     *  second time. It waits here until the state id stops moving, then measures the same grid against the same
     *  snapshot once more ({@link #settleId} holds the last seen id). */
    private static boolean settling;
    private static int settleId, settleQuiet, settleMoves;
    /** Whether this visit already fetched the server's screen once after the result slot read empty on a grid that
     *  still holds sets: the empty read was this side's stale mirror, not a real empty (cursor picks that fill the
     *  inventory's last free space make the mirror read empty while the server's answer keeps every set). One re-read
     *  per visit, then the other paths decide from the server's own screen. */
    private static boolean giveUpSynced;
    /** The last forced screen sync scheduled for the recipe being worked on
     *  could not complete: {@link #syncClick} found no empty slot, or its answer never came: this side's mirror was
     *  never verified against the server, so this recipe's clicks stay one per step, because a burst's guessed moves
     *  are only safe to send from a mirror the server has just confirmed. Every scheduled sync re-decides the flag. */
    private static boolean syncUnverified;
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
    /** Recipes whose give-up of a click batch was already said in chat in this run: the
     *  walker hands a given-up recipe again on a later pass, and the retry may well work once a new tool is crafted. */
    private static final java.util.Set<String> givenUp = new java.util.HashSet<>();
    /** Recipes whose missing tool was already named in chat in this run (see {@link #addMissingTool}). */
    private static final java.util.Set<String> toolNamed = new java.util.HashSet<>();
    /** Per recipe: the short reason the run gave up on its visit last (see {@link #abandonVisit}), for the end-of-run line. */
    private static final Map<String, String> giveUpWhy = new HashMap<>();
    /** Recipes whose click loop already took the "the result slot read empty with crafts still left, fill the grid
     *  afresh instead of giving up" detour once this run; the second time the other give-up paths decide. Cleared with
     *  every run. */
    private static final java.util.Set<String> refills = new java.util.HashSet<>();
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
     * Starts crafting the chain. {@code ignoreInventory} crafts the wanted amount again even if the inventory
     * already holds it; false, the default, crafts only what is missing. (NEI has these
     * two on the opposite modifiers.)
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
                // Say which ingredient is short here, to tell a real shortage from JEI
                // refusing a fill this side thinks it can do (a worn tool's id stands for its plain item)
                if (!can) NeiAddon.LOGGER.info("Autocrafting: cannot craft {} now (recipe found: {}; {})", recipeId, target != null, missingFor(recipeId));
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
                return can;
            }
        });
        // first make this side's view of the screen the server's (an item seen here but gone there makes JEI's transfer fail)
        phase = Phase.SYNC;
        syncRetries.clear();
        // The batch sync and the no-craft guard are per run
        batchSynced.clear();
        batchZeroCrafts.clear();
        resumeAfterSync = Phase.NEXT;
        resumeAfterFill = Phase.NEXT;
        fillCleared.clear();
        fillClearTries = 0;
        planDumped = false;
        topPlanTarget.clear();
        topMinItems = 0;
        topRetried = false;
        // The deferred turns and the top-up's did-not-arm note are per run as well
        deferred.clear();
        topUpWhy = null;
        visitCrafts = 0;
        singleClicks.clear();
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
        // A click batch never inherits these (every site that enters Phase.CLICKS assigns them first), but reset them
        // with the other per-run state so a future fifth site cannot either
        hadTool = false;
        clicksMade = 0;
        // A fresh run may burst again even if the last one fell back to single clicks
        clicksSent = 0;
        shiftStep = false;
        stepCredit = 0;
        burstHere = true;
        settling = false;
        giveUpSynced = false;
        syncUnverified = false;
        givenUp.clear();
        toolNamed.clear();
        giveUpWhy.clear();
        refills.clear();
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
        fillCleared.clear();
        fillClearTries = 0;
        planDumped = false;
        topPlanTarget.clear();
        topMinItems = 0;
        topRetried = false;
        // With the other per-run state, and next to ae2Retried's own clear below
        deferred.clear();
        topUpWhy = null;
        visitCrafts = 0;
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
                    // When the visit before this one crafted exactly one set while the plan still wanted more, log the
                    // clamp that kept the batch top-up from arming (the silent one-craft-at-a-time path).
                    if (topUpWhy != null && visitCrafts == 1 && walker.remaining() > 0) NeiAddon.LOGGER.info(
                        "Autocrafting: the batch top-up for {} did not arm ({}); crafting one set at a time", current, topUpWhy);
                    topUpWhy = null;
                    visitCrafts = 0;
                    // JEI's grid fill first empties a grid that is not empty itself, by moving its old contents into
                    // the inventory without asking the server. When the server puts a stack (the previous batch's tool
                    // above all) back in another slot than this side guessed, the fill names a slot that is empty on
                    // the server as an ingredient source and the whole transfer is refused ("was given an empty
                    // inventory slot as an ingredient source"), for a single set just as for a batch. So the grid is
                    // emptied first with the cleanup's verified clicks (each answered by the server), one slot per
                    // tick, and every fill starts from an empty grid.
                    if (!ae2) {
                        for (Slot slot : gridSlots) {
                            if (slot.hasItem()) {
                                if (fillClearTries >= GRID_CLEAR_ROUNDS) {
                                    // an inventory without room: the fill goes on top of the items instead
                                    NeiAddon.LOGGER.info("Autocrafting: the grid keeps its contents; filling it anyway (grid:{})", describeGrid());
                                    break;
                                }
                                // What the grid holds as it is emptied, so the log shows
                                // which items went back to the inventory and in what condition (a tool's damage)
                                NeiAddon.LOGGER.info("Autocrafting: emptying the grid before the next fill (grid:{})", describeGrid());
                                fillCleared.clear();
                                resumeAfterFill = Phase.NEXT;
                                awaiting = false;
                                waited = 0;
                                phase = Phase.GRID_CLEAR;
                                return;
                            }
                        }
                    }
                    String recipeId = walker.next();
                    if (recipeId == null) {
                        // done: whatever is still on the cursor or in the grid goes back into the inventory
                        reportUnfinished(mc);
                        startCleanup();
                        return;
                    }
                    if (!planDumped) {
                        planDumped = true;
                        // The "give missing" side and the crafting side can disagree on how many crafts a GregTech
                        // chain wants (one run planned ~144 plate crafts, far over the tooltip's plan, and ate the
                        // whole titanium supply, starving the ring/rotor/vent steps). The plan the walker starts from
                        // is dumped so the log can be compared with the "Gave the missing ingredients" line (join on
                        // the item id) and with the visit/count lines (join on the recipe id). The maths is only filled
                        // in inside the walker.next() above, which is why the dump sits here and not in start().
                        for (Map.Entry<ChainItem, Long> e : math.requiredAmount.entrySet()) {
                            if (e.getValue() <= 0) continue;
                            NeiAddon.LOGGER.info("Autocrafting plan: {} x{} of {} (wanted by recipe {})",
                                e.getKey().type, e.getValue(), e.getKey().item, e.getKey().recipeId);
                        }
                    }
                    // What this visit still has to craft, per recipe
                    NeiAddon.LOGGER.info("Autocrafting visit: {} still wants {} crafts", recipeId, walker.remaining());
                    // a retry waits for this same recipe: a mark left by a recipe the chain moved on from is stale
                    ae2Retried.removeIf(id -> !id.equals(recipeId));
                    RecipeLookup.Target<?> target = target(recipeId);
                    // batching: as many sets as the grid takes while more crafts are wanted than the last full grid of
                    // this recipe held (unknown at first: try); one set otherwise.
                    // Never batch into a grid that still holds the previous batch's tool. JEI's batched fill first
                    // moves the old grid contents out optimistically; when the server puts them back elsewhere (a
                    // hammer lands on another hammer), the packet it then sends maps an ingredient to an inventory slot
                    // that is empty there and the whole fill is refused ("was given an empty inventory slot as an
                    // ingredient source"), which costs the recipe its pass. A single-set fill plus the top-up batching
                    // below takes the same number of crafts with verified clicks instead.
                    boolean toolInGrid = false;
                    for (Slot slot : gridSlots) {
                        if (slot.hasItem() && Tools.isTool(slot.getItem())) {
                            toolInGrid = true;
                            break;
                        }
                    }
                    // Nor does a batched fill go in for a recipe that takes an input which stacks to one (its tool):
                    // the viewer then fills the other slots to 64 and leaves the tool out ("no result after the
                    // transfer"). One set goes in instead and the top-up batches the rest; a recipe whose inputs stack
                    // (or cannot be read) is batched normally.
                    full = !ae2 && !toolInGrid && !unstackableInput(recipeId) && !gridSlots.isEmpty() && walker.remaining() > 1
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
                        // Refused over an input the plan itself crafts: the visit is given back later in this run
                        // instead of giving up on the recipe
                        if (!deferVisit(recipeId, why)) abandonVisit(recipeId, "the transfer was refused");
                        return;
                    }
                    ae2Retried.remove(recipeId);
                    current = recipeId;
                    // The viewer's fill does not carry the recipe's tool (it caps a tool recipe's fill at one set, and
                    // fills past that when the tool stays out of the grid). The tool is added after this fill's forced
                    // sync (phase ADD_TOOL), on the server's grid: clicks planned from this side's grid before that
                    // sync can pick a stale cell, and the server then has no tool in the grid. If no tool is to be had,
                    // no click could work, so the grid is left to the next visit's verified emptying and the recipe is
                    // deferred or given up in ADD_TOOL
                    if (!ae2) {
                        // JEI's grid fill is optimistic: it moves the items into the grid here without waiting for the
                        // server's answer. When the server refused that fill ("was given an empty inventory slot as an
                        // ingredient source"), this side would keep a grid and an inventory the server does not have,
                        // and the next fill would map ingredients to slots that are empty there. So every fill goes
                        // through the forced screen sync first, before the grid is read and before the next fill is
                        // planned: a refused fill then shows as an empty grid, which the no-result path below fetches
                        // and retries.
                        NeiAddon.LOGGER.info("Autocrafting: fetching the screen from the server after the grid fill of {}", recipeId);
                        awaiting = false;
                        waited = 0;
                        // Every scheduled sync re-decides the flag below, so one failed sync never demotes a recipe
                        // (this or a later one) whose own sync did go through
                        syncUnverified = false;
                        // A recipe with a tool ingredient goes on to ADD_TOOL on the synced screen
                        resumeAfterSync = toolIngredients(recipeId).isEmpty() ? Phase.WAIT_RESULT : Phase.ADD_TOOL;
                        phase = Phase.SYNC;
                        return;
                    }
                    phase = Phase.WAIT_RESULT;
                    waited = 0;
                }
                case ADD_TOOL -> {
                    // The screen the fill's sync fetched is the server's, so the tool goes in there
                    ToolFill fill = addMissingTool(mc, current);
                    if (fill == ToolFill.NOT_NEEDED) {
                        // the fill carried the tool (or it is in the grid already): no second sync, the JEI path costs no round trip
                        phase = Phase.WAIT_RESULT;
                        waited = 0;
                        return;
                    }
                    if (fill == ToolFill.PLACED) {
                        // the clicks are optimistic: the forced sync brings the server's grid back before the craft is read
                        awaiting = false;
                        waited = 0;
                        syncUnverified = false;
                        resumeAfterSync = Phase.WAIT_RESULT;
                        phase = Phase.SYNC;
                        return;
                    }
                    // no tool to be had (the player was told in chat): the visit is deferred or given up
                    if (!deferVisit(current, null)) abandonVisit(current, "no tool to be had");
                    if (fill == ToolFill.REFUSED_AFTER_CLICKS) {
                        // some tools were clicked in before the refusal: the forced sync tells the next visit what the server has
                        awaiting = false;
                        waited = 0;
                        syncUnverified = false;
                        resumeAfterSync = Phase.NEXT;
                        phase = Phase.SYNC;
                        return;
                    }
                    // nothing was clicked, and the screen was just synced: no extra sync needed
                    phase = Phase.NEXT;
                    return;
                }
                case GRID_CLEAR -> {
                    // the same wait for the answer as in the cleanup's clicks (send answers through forceAnswer)
                    if (awaiting) {
                        if (menu.getStateId() == clickState) {
                            if (++waited > TIMEOUT) {
                                // a slot the server will not empty (an inventory without room): go on regardless,
                                // the paths below still handle a grid that stays filled
                                NeiAddon.LOGGER.info("Autocrafting: the server did not answer a click emptying the grid");
                                awaiting = false;
                            } else return;
                        } else awaiting = false;
                    }
                    for (Slot slot : gridSlots) {
                        if (slot.hasItem() && fillCleared.add(slot)) {
                            send(mc, slot.index, true);
                            return;
                        }
                    }
                    // here: the grid is empty, or every filled slot was tried once and stayed: continue either way
                    fillCleared.clear();
                    boolean kept = false;
                    for (Slot slot : gridSlots) {
                        if (slot.hasItem()) {
                            kept = true;
                            break;
                        }
                    }
                    fillClearTries = kept ? fillClearTries + 1 : 0;
                    phase = resumeAfterFill;
                }
                case TRIM -> {
                    RecipeLookup.Target<?> target = target(current);
                    full = false;
                    if (target == null || !target.transfer(menu, mc.player, false)) {
                        NeiAddon.LOGGER.info("Autocrafting: transfer of {} was refused", current);
                        // The re-fill refusing over an input the plan crafts defers
                        // the visit instead of giving up on the recipe
                        if (!deferVisit(current, null)) abandonVisit(current, "the transfer was refused");
                        phase = Phase.NEXT;
                        return;
                    }
                    // Same forced sync as after every other fill, so WAIT_ONE_SET below
                    // looks at the server's grid and the next fill starts from a model the server agrees with
                    awaiting = false;
                    waited = 0;
                    resumeAfterSync = Phase.WAIT_ONE_SET;
                    phase = Phase.SYNC;
                }
                case WAIT_RESULT -> {
                    if (result.hasItem()) {
                        if (wrongResult(result)) return;
                        if (ae2) {
                            // AE2 refills the grid from the network after each craft: one craft action per wanted craft
                            clicksLeft = walker.remaining();
                            clicksMade = 0;
                            hadTool = false;
                            awaiting = false;
                            phase = Phase.CLICKS;
                            waited = 0;
                            clicks(mc, result);
                            return;
                        }
                        // Also after a single-set fill (see NEXT: no batched fill into a tool-carrying
                        // grid): the top-up batches the crafts itself, with verified clicks and its own server sync
                        if (gridSets() == 1 && walker.remaining() > 1 && startTopUp()) return;
                        long sets = full ? gridSets() : 1;
                        if (full) fullSets.put(current, sets);
                        if (full && batchSynced.add(current)) {
                            // JEI's (and EMI's) grid fill is optimistic: it clicks without forceAnswer, so the sets
                            // counted here come from this side's guess of the grid, while the shift-click below crafts
                            // every set the SERVER's grid holds. When the two grids differ ("no result after the
                            // transfer ...", fetching the screen from the server and trying again), the crafts counted
                            // from this snapshot are fewer than the ones made, "remaining" does not drain and the next
                            // pass crafts the same recipe again: the surplus the player sees. So the first craft of a
                            // batch starts with the same forced screen sync the no-result recovery uses further down:
                            // after it the sets, the clicks and their credit all come from the server's own screen. The
                            // sync is one round-trip per recipe BATCH; the batch itself then crafts with checked
                            // clicks, bursts included, from the server's screen as well.
                            NeiAddon.LOGGER.info("Autocrafting: fetching the screen from the server before the first batch craft of {}", current);
                            awaiting = false;
                            waited = 0;
                            // If the sync then finds no empty slot to sync through, or its answer never comes, this
                            // recipe stays unverified and its loop stays at one click per step; every scheduled sync
                            // re-decides the flag, none just inherits it
                            syncUnverified = false;
                            resumeAfterSync = Phase.WAIT_RESULT;
                            phase = Phase.SYNC;
                            return;
                        }
                        if (full && sets > walker.remaining()) {
                            // The grid holds more sets than crafts are left. One shift-click per step would let
                            // vanilla's doClick repeat loop craft the whole over-large grid and credit all of it, so
                            // the tail is trimmed instead: one set goes in, and the rest of this recipe is crafted one
                            // craft at a time. FAST takes this trimming route as well; its tool recipes still batch
                            // through the room-clamped startTopUp. The grid is emptied with verified clicks first (see
                            // Phase.GRID_CLEAR) and the re-fill then goes into an empty grid from Phase.TRIM: JEI would
                            // empty this full grid optimistically itself, and the same refusal as in NEXT could follow
                            // it.
                            fillCleared.clear();
                            resumeAfterFill = Phase.TRIM;
                            awaiting = false;
                            waited = 0;
                            phase = Phase.GRID_CLEAR;
                            return;
                        }
                        if (full) {
                            // The grid of a batch has been taken from the server's own screen above; craft it in
                            // checked steps (see startClicks): one shift-click per step, or cursor picks for a recipe
                            // in singleClicks or when the grid holds more sets than the inventory has room for or than
                            // is still wanted.
                            startClicks(mc, result);
                            return;
                        }
                        craft(mc, result);
                    } else if (++waited > (syncRetries.containsKey(current) ? TIMEOUT : FIRST_RESULT_TICKS)) {
                        // a first transfer gets less time: when it did nothing, the screen is fetched and it is tried again
                        if (syncRetries.merge(current, 1, Integer::sum) <= 1) {
                            // JEI's transfer did nothing: most likely this side saw an item the server does not have
                            NeiAddon.LOGGER.info("Autocrafting: no result after the transfer of {}, fetching the screen from the server and trying again", current);
                            resumeAfterSync = Phase.NEXT;
                            phase = Phase.SYNC;
                        } else if (deferVisit(current, null)) {
                            // The transfer did nothing over an input the plan itself crafts: the recipe gets its turn
                            // back later in this run instead of being given up
                            phase = Phase.NEXT;
                        } else {
                            NeiAddon.LOGGER.info("Autocrafting: no result after the transfer of {}, giving up on this recipe", current);
                            abandonVisit(current, "no result after filling the grid");
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
                        abandonVisit(current, "the grid did not go back to one set");
                        phase = Phase.NEXT;
                    }
                }
                case WAIT_CLEAR -> {
                    // only the server's answer counts: right after the click this side already shows the result slot empty
                    // (its own guess of the click), and counting the crafts from that guess would count one instead of all
                    // (the click always gets an answer, see forceAnswer)
                    if (menu.getStateId() != clickState && !result.hasItem()) {
                        // On a batch the count is taken exactly, without the floor at 1: a shift-click that crafted
                        // nothing on the server would otherwise drain one craft from the walker anyway, so the chain
                        // would treat the recipe as closer to done and craft the rest again on top of the surplus. A
                        // recipe with an unstackable input counts a measured 0 as 0 (see craftsDoneBatch); every other
                        // recipe keeps the single-click count, where a click that measured 0 still counts as one craft.
                        long done = (full || unstackableInput(current)) ? craftsDoneBatch() : craftsDone();
                        if (done == 0 && (full || unstackableInput(current))) {
                            if (!CraftCount.giveUp(batchZeroCrafts.merge(current, 1, Integer::sum))) {
                                // nothing was crafted: fetch the grid from the server and fill it again on that screen
                                NeiAddon.LOGGER.info("Autocrafting: the batch shift-click on {} crafted nothing on the server, fetching the screen and filling the grid again", current);
                                awaiting = false;
                                waited = 0;
                                resumeAfterSync = Phase.NEXT;
                                phase = Phase.SYNC;
                                return;
                            }
                            NeiAddon.LOGGER.info("Autocrafting: the batch shift-click on {} crafted nothing on the server, giving up on this recipe", current);
                            abandonVisit(current, "the server crafted nothing");
                            phase = Phase.NEXT;
                            return;
                        }
                        batchZeroCrafts.remove(current);
                        noteCrafted(done);
                        // The count a shift-click was credited with, and what is left to craft
                        // (with the grid the count was measured from, tools and their damage included)
                        NeiAddon.LOGGER.info("Autocrafting count: {} crafted {}, {} left (grid:{})", current, done, walker.remaining(), measuredNote);
                        phase = Phase.NEXT;
                    } else if (++waited > TIMEOUT) {
                        if (menu.getStateId() != clickState && result.hasItem() && craftsDoneExact() == 0 && roomFor(result.getItem())
                            && singleClicks.add(current)) {
                            // the server answered but the shift-click crafted nothing (some recipes or crafting-table
                            // mods only take the result a craft at a time): craft this recipe by clicking the result
                            NeiAddon.LOGGER.info("Autocrafting: shift-clicking the result of {} did nothing, crafting it one click at a time", current);
                            clicksLeft = Math.min(walker.remaining(), Math.max(1, gridSets()));
                            clicksMade = 0;
                            hadTool = hasToolInGrid();
                            // This recovery is the "one click at a time" path: it just watched a batch click fail, so
                            // it stays at one click per step. Adding to singleClicks also keeps the click loop on
                            // cursor picks for this recipe (the failed batch click was a shift-click, so CLICKS must
                            // not shift-click it either), for the whole run, not just this visit.
                            burstHere = false;
                            giveUpSynced = false;
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
                        waited = 0;
                        if (!storageTaken) snapshotStorage();
                        // The id still being clickState means the sync click's own answer never arrived: the mirror was
                        // not verified, the same as when syncClick finds no slot to sync through; the click loop of
                        // that recipe stays at one click per step
                        if (menu.getStateId() == clickState && (resumeAfterSync == Phase.WAIT_RESULT || resumeAfterSync == Phase.ADD_TOOL)) {
                            syncUnverified = true;
                        }
                        // A sync before the first craft of a batch goes back to that craft, so its grid
                        // sets and its snapshot are read from the screen the server just sent (see WAIT_RESULT)
                        phase = resumeAfterSync;
                        resumeAfterSync = Phase.NEXT;
                    } else if (!syncClick(mc)) {
                        if (!storageTaken) snapshotStorage();
                        waited = 0;
                        // No click could be sent, so no full screen came back: the mirror was not verified. A burst on
                        // an unverified mirror can hand the server a cursor claim it does not have, so this recipe's
                        // click loop stays at one click per step.
                        if (resumeAfterSync == Phase.WAIT_RESULT || resumeAfterSync == Phase.ADD_TOOL) syncUnverified = true;
                        // No slot to sync through: carry on without the sync
                        phase = resumeAfterSync;
                        resumeAfterSync = Phase.NEXT;
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
        abandonVisit(current, "the result slot held another item");
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

    /**
     * After JEI's fill, put the recipe's tools into the grid when the fill left them out. The viewer caps a tool
     * recipe's fill at one set, and when the tool stays out of the grid it fills several sets without it (upstream
     * atm9-expert#87), which ends in "no result after the transfer". The tools of the visited recipe are derived the
     * way {@link #ae2MissingTool} derives one ({@code Tools.isTool} over its planned ingredients): all of them, not the
     * first. The ring recipe wants a hammer AND a file, and one tool already in the grid does not cover the other. Each
     * missing one goes into the cell the recipe's own layout gives it (see {@link #toolCell}: a shaped recipe's tool
     * cell is fixed, and dropping the tool into some other empty cell, an out-of-shape cell, which is where the first
     * empty slot of such a fill usually is, leaves the grid not matching), using the fill's own optimistic clicks (one
     * picks the stack up whole, one puts it in; a tool stack is one item). It runs in phase {@link Phase#ADD_TOOL},
     * after the fill's forced sync, so the grid it reads and clicks into is the server's, not this side's guess; the
     * clicks are synced again by the caller. The result is {@link ToolFill#NOT_NEEDED} when there is nothing to do (the
     * recipe wants no tool, or every tool it wants is already in the grid), {@link ToolFill#PLACED} when all were
     * placed, and a refusal (see {@link ToolFill}) when a wanted tool is in neither the grid nor any reachable slot: no
     * click may be made for it, and the player is told so in chat.
     */
    private static ToolFill addMissingTool(Minecraft mc, String recipeId) {
        List<ChainItem> tools = toolIngredients(recipeId);
        if (tools.isEmpty()) return ToolFill.NOT_NEEDED;
        List<ChainItem> missing = new ArrayList<>();
        for (ChainItem tool : tools) {
            if (!gridHoldsTool(tool)) missing.add(tool);
        }
        if (missing.isEmpty()) return ToolFill.NOT_NEEDED;
        boolean clicked = false;
        // the grid indices already spoken for: the cells the fill placed into, and cells a tool went into a moment ago
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (int i = 0; i < gridSlots.size(); i++) {
            if (gridSlots.get(i).hasItem()) taken.add(i);
        }
        for (ChainItem tool : missing) {
            java.util.Set<String> kinds = toolKinds(tool);
            Slot from = null;
            for (Slot slot : menu.slots) {
                if (!slot.hasItem() || CraftSlots.isResult(slot) || !topSourceSlot(slot) || !Tools.isTool(slot.getItem())
                    || !kinds.contains(plainId(RecipeData.idOf(slot.getItem())))) continue;
                from = slot;
                break;
            }
            int cell = toolCell(mc, recipeId, tool, taken);
            Slot into = cell >= 0 ? gridSlots.get(cell) : null;
            if (into == null || into.hasItem()) {
                // No cell to be had - a shapeless recipe has no cells at
                // all (any empty cell is its cell), or the layout could not be read: the first empty cell
                into = null;
                for (Slot slot : gridSlots) {
                    if (slot.hasItem()) continue;
                    into = slot;
                    break;
                }
            }
            if (from == null || into == null) {
                String name = FluidStacks.name(FluidStacks.stackOf(tool.item)).getString();
                NeiAddon.LOGGER.info("Autocrafting: the grid fill of {} left the tool {} out, and neither the grid nor the inventory has one (grid:{})",
                    recipeId, name, describeGrid());
                if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component
                    .literal("§6[NEI Features] §ecould not craft " + recipeName(recipeId) + ": it needs a " + name + " and there is none in the inventory"));
                toolNamed.add(recipeId);
                return clicked ? ToolFill.REFUSED_AFTER_CLICKS : ToolFill.REFUSED;
            }
            clickNoWait(mc, from.index, 0);
            clickNoWait(mc, into.index, 0);
            clicked = true;
            taken.add(gridSlots.indexOf(into));
            NeiAddon.LOGGER.info("Autocrafting: the viewer's fill left the tool of {} out, adding it from the inventory", recipeId);
        }
        return ToolFill.PLACED;
    }

    /**
     * The tool ingredients of this recipe: {@code Tools.isTool} over its non-empty, non-fluid planned
     * ingredients. Read by the NEXT phase (does the recipe want a tool at all?) and by {@link #addMissingTool}.
     */
    private static List<ChainItem> toolIngredients(String recipeId) {
        List<ChainItem> tools = new ArrayList<>();
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor() || isFluid(ingredient)) continue;
            if (Tools.isTool(FluidStacks.stackOf(ingredient.item))) tools.add(ingredient);
        }
        return tools;
    }

    /** The plain ids standing for this tool ingredient: its own item and its tag options. */
    private static java.util.Set<String> toolKinds(ChainItem tool) {
        java.util.Set<String> kinds = new java.util.HashSet<>();
        kinds.add(plainId(tool.item));
        for (String option : candidates(tool)) kinds.add(plainId(option));
        return kinds;
    }

    /** Does the grid already hold a stack of this tool ingredient's kind? */
    private static boolean gridHoldsTool(ChainItem tool) {
        java.util.Set<String> kinds = toolKinds(tool);
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && Tools.isTool(slot.getItem()) && kinds.contains(plainId(RecipeData.idOf(slot.getItem())))) return true;
        }
        return false;
    }

    /**
     * The grid cell the visited recipe gives this tool. A shaped recipe's tool sits in a fixed cell, and the fill of
     * the viewer moves each ingredient of the recipe's own w x h grid to a slot of the 3x3 grid. The cell comes from
     * the vanilla recipe behind the chain's recipe id (see {@link #vanillaCrafting}). Which placement is used: for a
     * shaped recipe in a 9-slot grid, the one the fill really used, found by {@link #fillOffset} from the grid's
     * contents (a smaller-than-3x3 recipe is not always at the JEI table's place, see {@link #craftingIndex}, so the
     * tool must go by the fill's placement); otherwise the table's placement. {@code taken} holds the grid indices
     * already spoken for. -1 when no cell can be derived: a shapeless recipe has no cells at all (any empty cell is its
     * cell), the vanilla recipe cannot be found, or its size is not that of the grid; the caller then falls back to the
     * first empty cell.
     */
    private static int toolCell(Minecraft mc, String recipeId, ChainItem tool, java.util.Set<Integer> taken) {
        CraftingRecipe crafting = vanillaCrafting(mc, recipeId);
        if (crafting == null || crafting instanceof ShapelessRecipe) return -1;
        List<Ingredient> cells = crafting.getIngredients();
        int w;
        int h;
        if (crafting instanceof ShapedRecipe shaped) {
            // No reflection on the fields: the game loads vanilla from the SRG jar, where the field names are f_44146_
            // and the like, and name strings in reflection are not remapped. The getters are virtual calls, which are
            // remapped, as they are in the viewer's own code and for the lookup in {@link #vanillaCrafting}.
            w = shaped.getWidth();
            h = shaped.getHeight();
        } else {
            // a bridged recipe (GregTech's crafting recipes come to the grid as vanilla ones): its list is the
            // whole row-major square, with the empty cells marked empty in it; with the size known, the same
            // table of {@link #craftingIndex} places it (3x3 identity, 2x2 the left two columns)
            int square = (int) Math.round(Math.sqrt(cells.size()));
            if (square * square != cells.size()) return -1;
            w = h = square;
        }
        if (w < 1 || h < 1 || w > 3 || h > 3 || w * h != cells.size()) return -1;
        ItemStack want = FluidStacks.stackOf(tool.item);
        java.util.function.IntUnaryOperator used = i -> craftingIndex(i, w, h);
        if (crafting instanceof ShapedRecipe && gridSlots.size() == 9) {
            int[] offset = fillOffset(recipeId, cells, w, h, want);
            if (offset != null) used = i -> (offset[1] + i / w) * 3 + offset[0] + i % w;
        }
        for (int i = 0; i < cells.size(); i++) {
            Ingredient cell = cells.get(i);
            if (cell.isEmpty() || !cell.test(want)) continue;
            int at = used.applyAsInt(i);
            if (at < 0 || at >= gridSlots.size() || taken.contains(at)) continue;
            return at;
        }
        return -1;
    }

    /**
     * The offset {column, row} of the 3x3 grid where the fill really put a shaped
     * recipe of size w x h, or null to keep the JEI table's placement. Every offset that keeps the whole pattern
     * inside the grid is tried (see {@link #fitsPlacement}). The table's placement is used when it fits; otherwise
     * the one other offset that fits is the fill's; with none, or several, that fit without the table's, null.
     * Mirrored placements are not tried: the fill's mirror image is not a case seen in the logs.
     */
    private static int[] fillOffset(String recipeId, List<Ingredient> cells, int w, int h, ItemStack want) {
        if (fitsPlacement(cells, want, i -> craftingIndex(i, w, h))) return null;
        int[] only = null;
        int fitting = 0;
        for (int ox = 0; ox <= 3 - w; ox++) {
            for (int oy = 0; oy <= 3 - h; oy++) {
                int x = ox;
                int y = oy;
                if (!fitsPlacement(cells, want, i -> (y + i / w) * 3 + x + i % w)) continue;
                only = new int[] {x, y};
                fitting++;
            }
        }
        if (fitting != 1) return null;
        NeiAddon.LOGGER.info("Autocrafting: the fill placed {} at column {}, row {}, not at the JEI table's place; the tool goes by that placement",
            recipeId, only[0], only[1]);
        return only;
    }

    /**
     * Does a placement of a shaped recipe's cells fit the 3x3 grid as it is now?
     * {@code place} maps recipe index i to its grid index. Every grid slot outside the pattern must be empty. A
     * pattern cell that accepts the tool may be empty or hold an item its ingredient accepts (the tool); any other
     * non-empty cell must hold an item its ingredient accepts.
     */
    private static boolean fitsPlacement(List<Ingredient> cells, ItemStack want, java.util.function.IntUnaryOperator place) {
        boolean[] inPattern = new boolean[9];
        int[] at = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            at[i] = place.applyAsInt(i);
            if (at[i] < 0 || at[i] >= 9) return false;
            inPattern[at[i]] = true;
        }
        for (int k = 0; k < 9; k++) {
            if (!inPattern[k] && gridSlots.get(k).hasItem()) return false;
        }
        for (int i = 0; i < cells.size(); i++) {
            Ingredient cell = cells.get(i);
            if (cell.isEmpty()) continue;
            ItemStack held = gridSlots.get(at[i]).getItem();
            if (cell.test(want)) {
                if (!held.isEmpty() && !cell.test(held)) return false;
            } else if (held.isEmpty() || !cell.test(held)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The slot of the 3x3 grid the fill of the viewer moves ingredient
     * i of a shaped recipe's own (already trimmed) w x h grid to. Hardcoded from
     * {@code CraftingGridHelper.getCraftingIndex} as decoded from the JEI jar the build runs on (15.74.0.229).
     * Checked against the bytecode of JEI 15.20.0.116, 15.59.0.210 and 15.74.0.232 (the jars of the 1.20.1
     * instances of ATM9, ATM9 Expert and plain 1.20.1): the same table in all three, so no reflection is needed.
     * A 1-wide grid sits in the
     * middle column (1x1 dead centre, slot 4), a 1-tall grid in the middle row, a 2-wide grid in the left two
     * columns (2x2: 0,1,3,4), and a 2-tall grid in the BOTTOM two rows (3x2: 3,4,5,6,7,8); 3x3 is the identity.
     * Deliberately not the centring formula that spells the same table for six of the eight sizes: it gets the
     * 2-tall case wrong, and the fill does not do that.
     */
    private static int craftingIndex(int i, int w, int h) {
        if (w == 1) {
            if (h == 1) return 4;
            return i * 3 + 1;
        }
        if (h == 1) return i + 3;
        if (w == 2) {
            // the fill compares the ORIGINAL index each time, not the stepped one: 0,1 stay, 2 and 3 gain one
            // slot, 4 and 5 gain two (the left-two-columns map above: 0,1,3,4,6,7)
            if (i > 3) return i + 2;
            if (i > 1) return i + 1;
            return i;
        }
        if (h == 2) return i + 3;
        return i;
    }

    /**
     * The vanilla crafting recipe behind a chain recipe id: the id is
     * {@code <recipe type>|<vanilla id>} for a recipe known by name (see {@link RecipeShape#idOf}), and JEI only
     * fills a crafting grid from vanilla crafting recipes (GregTech's bridged ones included). Null for anything
     * else (a {@code #hash} id, the chain's root, no client level, no such recipe).
     */
    private static CraftingRecipe vanillaCrafting(Minecraft mc, String recipeId) {
        int bar = recipeId.indexOf('|');
        if (bar <= 0 || mc.level == null) return null;
        ResourceLocation vanilla = ResourceLocation.tryParse(recipeId.substring(bar + 1));
        if (vanilla == null) return null;
        Recipe<?> recipe = mc.level.getRecipeManager().byKey(vanilla).orElse(null);
        return recipe instanceof CraftingRecipe crafting ? crafting : null;
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

    /**
     * For the log, which of the chain's ingredients for this recipe the open screen's
     * inventory is short of for one craft (the fill check's scale): each ingredient line of the recipe is compared
     * with what the inventory holds of its candidates, without the subtype mark so a worn tool stands for its plain
     * item; fluids are left out, they are not items. Says so when nothing is short, which means JEI's own check
     * refused a fill this side believes the screen can do.
     */
    private static String missingFor(String recipeId) {
        if (math == null) return "no chain";
        Map<String, Long> have = new HashMap<>();
        for (Map.Entry<String, Long> e : inventory().entrySet()) {
            have.merge(plainId(e.getKey()), e.getValue(), Long::sum);
        }
        StringBuilder shortOf = new StringBuilder();
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor() || isFluid(ingredient)) continue;
            java.util.Set<String> options = new java.util.LinkedHashSet<>();
            options.add(plainId(ingredient.item));
            for (String option : candidates(ingredient)) options.add(plainId(option));
            long got = 0;
            for (String option : options) got += have.getOrDefault(option, 0L);
            if (got >= ingredient.factor) continue;
            if (shortOf.length() > 0) shortOf.append(", ");
            shortOf.append("short ").append(ingredient.factor - got).append('x').append(plainId(ingredient.item)).append(" (have ").append(got).append(')');
        }
        return shortOf.length() == 0 ? "nothing short here, the viewer refused the fill itself" : shortOf.toString();
    }

    /**
     * A visit that was refused, could not be re-filled, or left no result gets its turn back later in this same run
     * instead of giving up on the recipe, when what it waits for is an input this run's own plan still has to craft
     * (see {@link #waitsForPlannedInput}). {@link ChainAutocraft#defer()} re-adds the recipe so a later pass hands it
     * again once the input has been crafted, and keeps the passes alive while that is owed. Each recipe gets at most
     * {@link #DEFERRED_TURNS} deferred turns per run (counted here, separate from AE2's {@code ae2Retried}); after that
     * the visit is given up. AE2 visits keep their own retry handling and never defer. The caller abandons when this
     * returns false.
     */
    private static boolean deferVisit(String recipeId, String why) {
        if (ae2 || math == null || "recipe not found".equals(why)) return false;
        if (!waitsForPlannedInput(recipeId)) return false;
        if (deferred.merge(recipeId, 1, Integer::sum) > DEFERRED_TURNS) return false;
        NeiAddon.LOGGER.info("Autocrafting: {} is waiting for input it does not have yet, it gets another turn this run", recipeId);
        walker.defer();
        return true;
    }

    /**
     * Does this visit wait for an input the chain's plan crafts? {@link #missingFor} names what is short by item id
     * ("short 2xgtceu:titanium_ingot (have 0)"), and that counts when it names the output of a recipe of the plan (an
     * ingredient of another planned step: not the run's wanted item, which hangs on the chain's root recipe and is
     * never crafted as an input of a step). JEI's own refusal note is not matched by display name: a substring test
     * took "Iron Ingot" inside "Sterling Iron Ingot" and deferred visits waiting for something else. The ids of
     * {@link #missingFor} are what the refusals actually turn on.
     */
    private static boolean waitsForPlannedInput(String recipeId) {
        for (String part : missingFor(recipeId).split(", ")) {
            int at = part.indexOf('x');
            int space = part.indexOf(" (", at);
            if (!part.startsWith("short ") || at < 0 || space < 0) continue;
            if (plannedToCraft(part.substring(at + 1, space))) return true;
        }
        return false;
    }

    /** Is this item (plain id, worn subtypes stripped) an output of a planned recipe? */
    private static boolean plannedToCraft(String itemId) {
        String plain = plainId(itemId);
        for (ChainItem result : math.recipeResults) {
            if (result.recipeId != null && !ChainMath.ROOT_RECIPE_ID.equals(result.recipeId)
                && plainId(result.item).equals(plain)) return true;
        }
        return false;
    }

    /** Shift-clicks the result: vanilla crafts as long as the grid has a full set, and drops a result at the
     *  player's feet when the inventory has no room for it, so this checks room first. */
    private static void craft(Minecraft mc, Slot result) {
        ItemStack one = result.getItem();
        if (!one.isEmpty() && !roomFor(one)) {
            // This single shift-click would still craft and drop the result at
            // the player's feet: stop with the same reason and message the click loop's room check gives
            fail("the inventory has no room left for the result of " + recipeName(current));
            return;
        }
        before.clear();
        for (Slot slot : gridSlots) before.put(slot, slot.getItem().copy());
        forceAnswer();
        mc.gameMode.handleInventoryMouseClick(menu.containerId, result.index, 0, ClickType.QUICK_MOVE, mc.player);
        phase = Phase.WAIT_CLEAR;
        waited = 0;
    }

    /**
     * Craft what a verified grid holds with checked clicks instead of with one batch shift-click. The crafts of a batch
     * could only ever be guessed from how the grid looked before and after the click, across however many server
     * answers arrived in between, and that guess can go both ways on one verified grid (all of it gone after the click,
     * then exactly one set). Every wrong guess breaks the walker's maths: crafts credited that never happened leave
     * items missing later, crafts counted too few make the recipe be crafted again on top of the surplus. The click
     * loop below credits a craft only when the server's own answer shows that many sets consumed, so the count cannot
     * drift, and it never clicks past what is still wanted. One measurement per burst credits exactly what the grid
     * lost, a few times the speed (GTNH's own throttle branch batches its crafts the same way; see its unmerged
     * caedis/autocraft-throttle). The vanilla repeat loop inside one shift-click per step does the repetition, and only
     * the cursor-pick fallback still clicks in bursts. The grid's tools are not in the set count: the click loop's
     * count is capped by what the tools in the grid are estimated to hold left, but the one shift-click wears the tool
     * across all sets the grid holds; when the tool breaks on the way, the recipe is given up on (see {@link #clicks}),
     * not run into the ground.
     */
    private static void startClicks(Minecraft mc, Slot result) {
        long sets = gridSets();
        if (sets < 1) {
            // nothing but tools (or nothing) in the grid: a click here could never be checked, do not start a batch
            NeiAddon.LOGGER.info("Autocrafting: the grid of {} holds no set of materials to craft from, giving up on this recipe", current);
            abandonVisit(current, "the grid holds no materials to craft from");
            phase = Phase.NEXT;
            return;
        }
        long toolLeft = Long.MAX_VALUE;
        hadTool = false;
        for (Slot slot : gridSlots) {
            ItemStack in = slot.getItem();
            if (Tools.isTool(in)) {
                hadTool = true;
                toolLeft = Math.min(toolLeft, Tools.craftsLeft(in));
            }
        }
        if (toolLeft < sets) {
            // a GregTech tool can wear out earlier than this estimate of its crafts left (startTopUp caps its fill by
            // the same number). The estimate caps only the click loop's count: a shift-click step crafts across all the
            // grid's sets inside vanilla's repeat loop, and wears the tool across them. A tool that breaks on the way
            // is caught by the empty result slot path in clicks(), which gives up on the recipe and lets the chain go
            // on.
            NeiAddon.LOGGER.info("Autocrafting: the tool in the grid of {} holds an estimated {} crafts left but the grid holds {} sets, the one shift-click wears it across all of them", current, toolLeft, sets);
        }
        clicksLeft = Math.min(Math.min(walker.remaining(), sets), toolLeft);
        clicksMade = 0;
        // A burst needs a mirror the server has verified; when this recipe's
        // forced sync found no slot to sync through, the visit stays at one checked click per step
        burstHere = !syncUnverified;
        giveUpSynced = false;
        awaiting = false;
        lastWasCraft = false;
        waited = 0;
        // This visit must not credit against the last visit's grid either: GRID_CLEAR handed the old grid's leftovers
        // to the bag and the next fill put different items in those slots, so the empty-result read path starts from no
        // snapshot at all until this visit's own first snapshot() before a send
        before.clear();
        stepCredit = 0;
        phase = Phase.CLICKS;
        clicks(mc, result);
    }

    /**
     * How many crafts the last shift-click did, from the grid before and after: each craft takes one item from every
     * filled slot. A slot whose item changed (a bucket left behind) or emptied counts as all used. Never below 1: a grid
     * of nothing but tools cannot be measured (a tool is not used up), and one set was asked for.
     */
    private static long craftsDone() {
        return CraftCount.single(craftsMeasured());
    }

    /**
     * The same count for a batch shift-click, without the floor at 1 ({@link CraftCount#batch(long)}). The batch is the
     * one the server decides on (vanilla crafts every set its own grid holds), so counting it from a grid this side
     * only guessed is already a risk, and flooring a click that crafted nothing at one craft makes the walker treat the
     * recipe as closer to done and craft the rest again on top (more than the player asked for). A grid of nothing but
     * tools cannot be measured at all, so there the one-craft guess stays. A batch is crafted in checked steps, each
     * credited from the server's answer, and the single-set paths count through {@link #craftsDone()}. This count is
     * still used in {@link Phase#WAIT_CLEAR} for a full (batched) fill, and for a recipe with an item that stacks to
     * one (a GregTech tool, see {@link #unstackableInput(String)}).
     */
    private static long craftsDoneBatch() {
        return CraftCount.batch(craftsMeasured());
    }

    /**
     * The smallest drop over the grid's slots that are used up, or {@link Long#MAX_VALUE} when every filled slot holds a
     * tool (see {@link #craftsDone()}).
     */
    private static long craftsMeasured() {
        long done = Long.MAX_VALUE;
        // Keep a one-line picture of what this count was measured from, so the log shows
        // the grid the crafts were counted on (and how far the tool wore while they were made)
        StringBuilder note = new StringBuilder();
        for (Map.Entry<Slot, ItemStack> e : before.entrySet()) {
            ItemStack was = e.getValue();
            if (was.isEmpty()) continue;
            ItemStack now = e.getKey().getItem();
            // a tool is not used up (it wears, or breaks on its last craft): the other slots tell how many crafts were done
            if (Tools.isTool(was)) {
                note.append(' ').append(RecipeData.idOf(was)).append("(tool ");
                note.append(now.isEmpty() ? "worn out" : "damage " + now.getDamageValue() + '/' + was.getMaxDamage());
                note.append(')');
                continue;
            }
            boolean same = ItemStack.isSameItemSameTags(was, now);
            long left = same ? now.getCount() : 0;
            note.append(' ').append(RecipeData.idOf(was)).append(' ').append(was.getCount()).append("->").append(same ? Long.toString(left) : "changed");
            done = Math.min(done, CraftCount.slotDrop(was.getCount(), same, now.getCount()));
        }
        measuredNote = note.toString();
        return done;
    }

    /** The grid the last {@link #craftsMeasured()} counted from, for the log. */
    private static String measuredNote = "";

    /** How many full sets the crafting grid holds: the smallest stack among its filled slots. */
    private static long gridSets() {
        long sets = Long.MAX_VALUE;
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && !Tools.isTool(slot.getItem())) sets = Math.min(sets, slot.getItem().getCount());
        }
        return sets == Long.MAX_VALUE ? 0 : sets;
    }

    /** Whether the grid holds a tool: its wear, unlike the materials, is not measurable
     *  by the click loop's counts, and it can break before the materials run out. */
    private static boolean hasToolInGrid() {
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && Tools.isTool(slot.getItem())) return true;
        }
        return false;
    }

    /**
     * Whether the recipe takes an item that stacks to one (a GregTech tool, or any other unstackable item) into a grid
     * slot. Read from the chain's planned ingredients of this recipe, as {@link #addMissingTool} reads them, skipping
     * the ingredients with no factor ({@link ChainItem#emptyFactor()}) and the fluids. An ingredient counts when its
     * item or any option it accepts (a tag's members) stacks to one; an item whose stack cannot be read counts as
     * stackable (the batched fill as usual).
     */
    private static boolean unstackableInput(String recipeId) {
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor() || isFluid(ingredient)) continue;
            for (String id : ingredient.options) {
                if (unstackable(id)) return true;
            }
        }
        return false;
    }

    /** Whether an item id names an item that stacks to one (a fluid id is never one). */
    private static boolean unstackable(String id) {
        if (FluidStacks.isFluidId(id)) return false;
        ItemStack stack = FluidStacks.stackOf(id);
        return !stack.isEmpty() && stack.getMaxStackSize() == 1;
    }

    /** A one-line picture of the grid for the log: count, id, and a tool's damage. */
    private static String describeGrid() {
        StringBuilder grid = new StringBuilder();
        for (Slot slot : gridSlots) {
            ItemStack in = slot.getItem();
            if (in.isEmpty()) continue;
            grid.append(' ').append(in.getCount()).append('x').append(RecipeData.idOf(in));
            if (Tools.isTool(in)) grid.append("(damage ").append(in.getDamageValue()).append('/').append(in.getMaxDamage()).append(')');
        }
        return grid.toString();
    }

    /** The slots of the menu's crafting grid, or none if unknown (see {@link CraftSlots#grid}). */
    private static List<Slot> craftingGrid(AbstractContainerMenu m) {
        return CraftSlots.grid(m);
    }

    // ---------------------------------------------------------------- Faster Autocrafting: exact counts by clicking

    /**
     * One step of crafting {@link #clicksLeft} items from a full grid by shift-clicking the result slot (the crafted
     * stacks go straight into the player's inventory). The step sends one shift-click, because vanilla repeats the
     * craft inside that one click: {@code AbstractContainerMenu.doClick}'s QUICK_MOVE branch loops
     * {@code quickMoveStack} while the result slot keeps yielding the same item, crafting until the grid runs out, so a
     * second shift-click after the grid-draining one is a no-op anyway. The cursor-pick PICKUP fallback
     * ({@link #burstSize} clamp, bursts of up to {@link #CLICK_BURST} clicks) still picks one result per click. The
     * answers of the step are its whole measurement: every click carries a stale state id, so the server answers each
     * one with a full copy of its screen, and a burst is credited whole when its grid lost as many sets as it held. A
     * burst that measures fewer than it sent first waits for the answers to stop coming and measures the same grid once
     * more (see {@link #settling}); still fewer, and only the measured ones are credited, the loop says so in the log
     * and clicks one at a time for the rest of the visit. The step stops with a clear message when the inventory has no
     * room left for one more result, because a shift-click whose result fits nowhere still crafts and drops the
     * leftover at the player's feet. Recipes the WAIT_CLEAR recovery pinned in {@link #singleClicks}, and a grid
     * holding more sets than the inventory has room for result stacks, keep the cursor picks and deposits; the grid's
     * unused sets stay for the next transfer (JEI empties the grid first) or the cleanup. Anything unexpected stops
     * autocrafting through the cleanup.
     */
    private static void clicks(Minecraft mc, Slot result) {
        if (ae2) {
            ae2Clicks(mc, result);
            return;
        }
        if (settling) {
            // A burst that measured short may be only its last answers, still on the way: the server answers every
            // click of the burst back-to-back, but a server tick can split the answers over more than one client tick.
            // Wait for the id to go quiet, the way SYNC and RECHECK do (SETTLE_TICKS quiet steps, with their own
            // counter: the shared settle belongs to syncClick). Then measure the same grid against the same snapshot
            // once more, and credit a burst as short only if it is still short. The id also moves with changes to the
            // player's own slots, which are part of this menu. More moves than the burst's outstanding answers can
            // explain mean the screen is being changed while this watches (another player at the same table, a sort, a
            // mod putting items into the player). Then the credit comes from what the grid shows now, not from a quiet
            // that may never come. That move budget counts a burst's outstanding answers, and the one shift-click step
            // has exactly one of them, so it stops watching after SETTLE_TICKS moves. A busy player inventory can move
            // the id on nearly every tick (an ME-fed one did), so that wait would end on the third move
            // without awaiting the click's own full-screen answer, and the credit would measure 1 set of a 64-set grid
            // the click had already consumed. A shift step's answer can be the 40th state-id change in a busy
            // inventory, so for that kind of step keep waiting for the quiet the same way, but do not give up on moves
            // until TIMEOUT of them have passed: the same wait the click loop already gives an answer before it calls
            // the server deaf. Bursts keep their own budget.
            int moveBudget = shiftStep ? TIMEOUT : clicksSent - 1 + SETTLE_TICKS;
            if (menu.getStateId() != settleId) {
                settleId = menu.getStateId();
                settleQuiet = 0;
                if (++settleMoves <= moveBudget) return;
            } else if (settleQuiet++ < SETTLE_TICKS) {
                return;
            }
            settling = false;
            if (creditBurst(false)) return;
        }
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) fail("the server did not answer a click");
                return;
            }
            awaiting = false;
            if (lastWasCraft && creditBurst(true)) return;
        }
        ItemStack carried = menu.getCarried();
        if (clicksLeft <= 0) {
            if (!carried.isEmpty()) {
                deposit(mc, carried);
                return;
            }
            // A batch of checked clicks logs the count line too, same shape as the WAIT_CLEAR one above (measured from
            // the click loop, not from a guess; its grid column shows the grid left over, where WAIT_CLEAR's shows the
            // one the crafts were measured from)
            NeiAddon.LOGGER.info("Autocrafting count: {} crafted {}, {} left (grid:{})", current, clicksMade, walker.remaining(), describeGrid());
            phase = Phase.NEXT;
            return;
        }
        ItemStack out = result.getItem();
        if (out.isEmpty()) {
            // Before deciding off this one empty read, make the server show its screen once per visit and look again.
            // An empty result slot on a grid that still holds sets is more likely this side's stale mirror than a real
            // empty (cursor picks that fill the inventory's last free space can stop a burst exactly there, while the
            // server's answer still holds every set). The sync is Phase.SYNC's own; the log names the case, so a run
            // shows what the server's screen really holds if the read stands.
            if (!ae2 && !giveUpSynced) {
                // The flag and the log belong to the sync that really gets
                // scheduled: an empty read while the cursor still holds picks only stows them, and the retry stays
                // armed for the next tick, which is when the deposit's own answer has just been applied anyway
                if (!carried.isEmpty()) {
                    deposit(mc, carried);
                    return;
                }
                giveUpSynced = true;
                NeiAddon.LOGGER.info("Autocrafting: the result slot of {} read empty, fetching the server's screen once more before deciding", current);
                awaiting = false;
                waited = 0;
                resumeAfterSync = Phase.CLICKS;
                phase = Phase.SYNC;
                return;
            }
            // The sync above made the server show its screen, so before the refill below the grid's loss is read again:
            // every credit of this step is remembered in {@link #stepCredit}, and the mirror can catch up late on
            // crafts the click already made. What the grid now shows lost, minus what this step was credited for, is
            // credited here, so a refill never crafts those sets a second time. A refill only runs for a grid this
            // visit has really not consumed; a fully credited dry grid with crafts still owed takes it too.
            long caughtUp = craftsDoneExact();
            long extra = caughtUp > stepCredit ? caughtUp - stepCredit : 0;
            if (extra > 0) {
                if (!carried.isEmpty()) {
                    deposit(mc, carried);
                    return;
                }
                noteCrafted(extra);
                clicksLeft -= extra;
                clicksMade += extra;
                stepCredit += extra;
                NeiAddon.LOGGER.info("Autocrafting count: {} crafted {}, {} left (grid:{})", current, clicksMade, walker.remaining(), describeGrid());
                NeiAddon.LOGGER.info("Autocrafting: the grid of {} has since lost {} more sets than its clicks were credited for (grid now:{}), crediting those crafts too; the grid's picture of this visit's step may simply have caught up late",
                    current, extra, describeGrid());
                if (clicksLeft <= 0) {
                    phase = Phase.NEXT;
                    return;
                }
            }
            // A visit that still owes crafts does not hand the recipe to the give-up or stop paths on an empty preview.
            // Once per recipe per run it takes the normal round instead. GRID_CLEAR hands whatever is left in the grid
            // back to the bag, the walker is not told to skip the recipe, and the next visit fills and syncs the grid
            // afresh: the fill's own answer repopulates a preview the server left empty over an intact grid, and a grid
            // the capped fill had already consumed is simply refilled. The second empty read goes to the paths below.
            if (clicksLeft > 0 && !refills.contains(current)) {
                if (!carried.isEmpty()) {
                    deposit(mc, carried);
                    return;
                }
                // The budget is claimed only when the detour is really
                // taken, so a tick that only stows the cursor does not burn the recipe's one refill
                refills.add(current);
                NeiAddon.LOGGER.info("Autocrafting: the result slot of {} read empty with {} crafts left, filling its grid afresh instead of giving up (grid:{})", current, clicksLeft, describeGrid());
                phase = Phase.NEXT;
                return;
            }
            // The tool a batch was capped by can wear out before its estimate says, and vanilla empties the tool's own
            // slot on the craft that breaks it, so the live grid may hold no tool any more: hadTool remembers that this
            // batch's grid had one. The recipe is given up on and the chain goes on with the rest; anything else stays
            // a hard stop.
            if (hadTool || hasToolInGrid()) {
                if (!carried.isEmpty()) {
                    deposit(mc, carried);
                    return;
                }
                // the crafts already credited still get their count line; the sentence names what can be seen, not a
                // cause this side cannot prove (an empty result slot with a tool in the grid can also be a desync)
                NeiAddon.LOGGER.info("Autocrafting count: {} crafted {}, {} left (grid:{})", current, clicksMade, walker.remaining(), describeGrid());
                NeiAddon.LOGGER.info("Autocrafting: the result slot of {} went empty although its grid had a tool when the batch started (grid now:{}), giving up on this recipe",
                    current, describeGrid());
                // player-visible: say here in chat that this recipe was given up on and the chain goes on with the rest
                // (reportUnfinished then leaves it out, so it is named once); the walker
                // may hand the recipe again on a later pass and a later pass may succeed, so chat says it once per run
                if (givenUp.add(current) && mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "§6[NEI Features] §eAutocrafting gave up on " + recipeName(current) + " for now; the rest of the chain goes on."));
                abandonVisit(current, "the result slot went empty");
                phase = Phase.NEXT;
                return;
            }
            fail("the result slot is empty although sets are left in the grid");
            return;
        }
        if (wrongResult(result)) return;
        // Recipes whose batch shift-click did nothing on the server (the WAIT_CLEAR recovery marks them in
        // singleClicks) pick the result onto the cursor and deposit it, because the shift-click is the very click that
        // failed on them. And when the grid holds more sets than the inventory has room for result stacks, the one
        // shift-click's doClick repeat loop would still craft all of them and drop every result past the room at the
        // player's feet (CraftingMenu.quickMoveStack's index-0 branch, player.drop), so such steps take the PICKUP
        // fallback too: its burst is clamped by burstSize and stackRoom, and a pick onto the cursor never drops. With
        // room 0 there is no demotion: the room-for-one check below stops then, with the clear "no room left for the
        // result" message (the PICKUP route would only stop one cursor-stack later, with a vaguer one).
        int room = stackRoom(out, (int) Math.min(gridSets(), Integer.MAX_VALUE));
        // The plan cap: the one shift-click repeats inside vanilla's loop until the grid is dry, so it must never face
        // a grid holding more sets than this visit still owes crafts (past the plan, that is over-crafting). The
        // cursor-pick route sends clicks that burstSize already caps by clicksLeft.
        boolean cursorPick = singleClicks.contains(current) || (room > 0 && gridSets() > room)
            || (room > 0 && gridSets() > clicksLeft);
        if (!cursorPick && !carried.isEmpty()) {
            // The player holds something on the cursor: put it away first (the fits check of the cursor picks does this
            // on its own), so a shift-click burst never starts while this side's cursor and the server's disagree
            deposit(mc, carried);
            return;
        }
        if (cursorPick) {
            boolean fits = carried.isEmpty()
                || ItemStack.isSameItemSameTags(carried, out) && carried.getCount() + out.getCount() <= carried.getMaxStackSize();
            if (!fits) {
                deposit(mc, carried);
                return;
            }
        } else if (!roomFor(out)) {
            // A vanilla shift-click whose result has no room left in the inventory still crafts and drops the leftover
            // result at the player's feet (the index-0 branch of CraftingMenu.quickMoveStack, player.drop), so this
            // step stops here with the reason instead of dropping results the player did not ask for.
            NeiAddon.LOGGER.info("Autocrafting count: {} crafted {}, {} left (grid:{})", current, clicksMade, walker.remaining(), describeGrid());
            fail("the inventory has no room left for the result of " + recipeName(current));
            return;
        }
        snapshot();
        // One shift-click per step: vanilla's doClick repeat loop runs the crafts of the whole grid inside that one
        // click, so a second shift-click after the grid-draining one would be a no-op. Only the cursor-pick PICKUP
        // fallback (recipes pinned in singleClicks, and grids demoted above for lack of room) bursts, one pick per
        // click.
        clicksSent = cursorPick ? burstSize(out) : 1;
        shiftStep = !cursorPick;
        send(mc, result.index, !cursorPick);
        // The rest of the burst: the very vanilla click the first send() wraps, sent straight (the state id this side
        // claims is already stale from forceAnswer, so the server will answer every one of them with a full screen) and
        // processed in order. The shift-click path has no rest to send, its repetition is vanilla's loop inside the
        // first click; what is left here are the cursor picks. The credit block at the top measures the step from the
        // grid after the answers, and waits for late answers before believing a short one (see {@link #settling})
        for (int i = 1; i < clicksSent; i++) mc.gameMode.handleInventoryMouseClick(menu.containerId, result.index, 0,
            cursorPick ? ClickType.PICKUP : ClickType.QUICK_MOVE, mc.player);
        lastWasCraft = true;
    }

    /**
     * Credit the outstanding burst of result clicks against the snapshot taken before it. Only what the grid itself
     * lost counts, and {@link #clicksSent} says how many clicks the burst sent. A burst that consumed fewer is credited
     * only the measured crafts, said in the log, and the rest of the visit clicks one at a time. When
     * {@code allowRetry} is set, the burst sent more than one click and the credit has not waited for late answers
     * yet, the credit first waits (see {@link #settling}) and this runs again on the same grid. That way an answer that
     * landed in a later tick is folded into the credit, instead of leaving the walker owing crafts the server already
     * made. A burst that consumed nothing, or crafted more times than the snapshot's grid held whole sets, stops the
     * run, with the count line of the crafts credited so far first. Returns true when the step must stop here (the
     * credit waits, or the run stopped).
     */
    private static boolean creditBurst(boolean allowRetry) {
        long done = craftsDoneExact();
        // Under shift-click bursts the common reason a burst crafts nothing from the start is that the inventory has no
        // room (the room pre-check stops for that), so name that in the message when it is what this side sees now
        Slot r = resultSlot(menu);
        String roomNote = r != null && r.hasItem() && !roomFor(r.getItem()) ? " (the inventory has no room for the result)" : "";
        if (done == 0 && shiftStep && allowRetry) {
            // A shift-click answer that lags COMPLETELY would stop the run below as "crafted nothing" although the
            // click's loop crafted the whole grid: the same quiet re-measure comes first; if the click really crafted
            // nothing, this runs again with allowRetry false and stops as it does then.
            settling = true;
            settleId = menu.getStateId();
            settleQuiet = 0;
            settleMoves = 0;
            return true;
        }
        // Vanilla's doClick repeat loop crafts on inside one shift-click until the grid runs out, so one click can
        // craft the whole grid (a shift-click burst once crafted 64 times without losing a single item). The most a
        // step can craft is the whole sets its snapshot's grid held, the same grid the credit measures against; more
        // than that means the grid or the credit cannot be trusted.
        if (done == 0 || done > snapshotSets) {
            NeiAddon.LOGGER.info("Autocrafting count: {} crafted {}, {} left (grid:{})", current, clicksMade, walker.remaining(), describeGrid());
            fail(done == 0 ? "a click on the result crafted nothing" + roomNote
                : "the clicks on the result crafted " + done + " times, more than the grid holds sets (" + snapshotSets + ")");
            return true;
        }
        if (done < clicksSent && allowRetry && clicksSent > 1) {
            // under-counting a burst whose last answers have not landed would leave the walker owing crafts the server
            // already made: wait for the answers to stop and measure this same grid once more before believing it
            settling = true;
            settleId = menu.getStateId();
            settleQuiet = 0;
            settleMoves = 0;
            return true;
        }
        if (shiftStep && done < snapshotSets && allowRetry) {
            // The same wait for the one shift-click step: vanilla's repeat loop crafts the whole grid inside that
            // click, and the first answer that moves the state id can lag the crafts it answers. Without this wait the
            // walker would be left owing up to 63 crafts the server had already made. The expectation for this step is
            // the whole sets the snapshot's grid held (the plan cap makes that true), so anything short of it waits for
            // the id to go quiet and is measured against the same grid once more before it is credited.
            settling = true;
            settleId = menu.getStateId();
            settleQuiet = 0;
            settleMoves = 0;
            return true;
        }
        if (done < clicksSent) {
            // A burst still short after the answers stopped (a tool's wear estimate going wrong, or anything else
            // unexpected): credit only the crafts the grid proves, say so, and leave the rest of this visit to one
            // click per step; the next visit may burst again
            NeiAddon.LOGGER.info("Autocrafting count: {} crafted {}, {} left (grid:{})", current, clicksMade, walker.remaining(), describeGrid());
            NeiAddon.LOGGER.info("Autocrafting: the last {} clicks on the result of {} consumed only {} of its sets (grid now:{}),"
                    + " crediting those and clicking one at a time from here{}", clicksSent, current, done, describeGrid(), roomNote);
            burstHere = false;
        }
        noteCrafted(done);
        clicksLeft -= done;
        clicksMade += done;
        // Remember what this step's credits claimed against its snapshot, so the empty-result read path can tell a
        // mirror that caught up late from a fill that really never crafted
        stepCredit = done;
        return false;
    }

    /**
     * How many result clicks the current PICKUP-fallback step sends: at most {@link #CLICK_BURST}, and never more than
     * what is still wanted, what the grid holds in sets, or what its tools are estimated to hold left, and by how many
     * result stacks the inventory can currently absorb (a shift-click that fits nowhere still crafts and drops the
     * result, so the room checks guard the run instead; {@link #clicks()} also checks that the inventory has room for
     * at least one result before it sends any burst). The shift-click step sends one click too (vanilla's repeat loop
     * inside it does the repetition). A step that must not burst (see {@link #burstHere}) sends one click.
     */
    private static int burstSize(ItemStack out) {
        if (!burstHere) return 1;
        long limit = Math.min(Math.min(gridSets(), stackRoom(out, CLICK_BURST)), clicksLeft);
        for (Slot slot : gridSlots) {
            ItemStack in = slot.getItem();
            if (Tools.isTool(in)) limit = Math.min(limit, Tools.craftsLeft(in));
        }
        return (int) Math.min(CLICK_BURST, Math.max(1, limit));
    }

    /**
     * How many result stacks fit in the player's inventory right now, counted the way vanilla's shift-click move does
     * it (partial merges over the main inventory and hotbar count, the offhand not; see {@link #roomFor}). A burst is
     * clamped to this so a nearly full inventory cannot leave clicks of a burst silently doing nothing and turn a
     * healthy burst into a measured-short one. The counting stops at {@code cap}: a burst passes {@link #CLICK_BURST},
     * and the tool recipe's batch fill (see {@link #startTopUp}) passes the sets it plans to craft, so a fill is
     * clamped by the real room and not by the burst size.
     */
    private static int stackRoom(ItemStack out, int cap) {
        int each = Math.max(1, out.getCount());
        long room = 0;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= 36 || !slot.mayPlace(out)) continue;
            ItemStack in = slot.getItem();
            int max = Math.min(out.getMaxStackSize(), slot.getMaxStackSize(out));
            if (in.isEmpty()) room += max;
            else if (ItemStack.isSameItemSameTags(in, out)) room += max - in.getCount();
            if (room / each >= cap) return cap;
        }
        return (int) Math.max(0, room / each);
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
        // The whole sets in this grid: vanilla's doClick repeat loop crafts inside one
        // shift-click until the grid runs out, so this is the most a step can craft; creditBurst() stops above it
        snapshotSets = gridSets();
        // The new step starts with nothing credited against this snapshot (see
        // {@link #stepCredit} and the empty-result read path of clicks())
        stepCredit = 0;
    }

    /** The whole sets the grid held at the last {@link #snapshot()}, the ceiling the
     *  burst credit stops at (see {@link #creditBurst}). */
    private static long snapshotSets;

    /** What the credits of the current click step have already claimed against that
     *  snapshot, zeroed by every {@link #snapshot()}: the empty-result read path compares it with what the grid now
     *  visibly lost, to tell a late mirror from a fill that never ran (see {@link #clicks}). */
    private static long stepCredit;

    /** Like {@link #craftsDone()}, but 0 when the grid did not change at all. */
    private static long craftsDoneExact() {
        long done = craftsMeasured();
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
    /**
     * Why the last {@link #startTopUp()} declined, in short: which clamp cut the
     * planned fill (or why there was nothing to top up); null while it has not been asked, or when the answer
     * was yes. The next {@link #startTopUp()} call overwrites it, and the visit that ends hands it to the log.
     */
    private static String topUpWhy;
    /** Crafts credited to the current recipe visit; the did-not-arm note is only
     *  printed for a visit that crafted exactly one set while the plan still wanted more. */
    private static long visitCrafts;

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
        // Record which clamp cuts the fill, so the one-set-a-time visit can say
        // why the top-up did not arm; this only records the clamps, the arithmetic below sets the fill
        topUpWhy = null;
        boolean tool = false;
        long toolCrafts = Long.MAX_VALUE;
        Map<Slot, Integer> perSet = new HashMap<>();
        for (Slot slot : gridSlots) {
            ItemStack in = slot.getItem();
            if (in.isEmpty()) continue;
            if (Tools.isTool(in)) {
                tool = true;
                long left = Tools.craftsLeft(in);
                toolCrafts = Math.min(toolCrafts, left);
                if (left < sets) {
                    sets = left;
                    topUpWhy = "the tool in the grid holds " + holds(left) + " more crafts";
                }
            } else {
                perSet.put(slot, in.getCount());
                long cap = Math.min(in.getMaxStackSize(), slot.getMaxStackSize(in)) / in.getCount();
                if (cap < sets) {
                    sets = cap;
                    topUpWhy = "the grid slot of " + RecipeData.idOf(in) + " holds at most " + cap + " sets";
                }
            }
        }
        if (!tool || perSet.isEmpty()) {
            topUpWhy = tool ? "the grid holds nothing but its tool" : "the grid holds no tool";
            return false;
        }
        // what the inventory has of each grid item, shared between the grid slots that want it
        StringBuilder have = new StringBuilder();
        for (Map.Entry<Slot, Integer> e : perSet.entrySet()) {
            ItemStack in = e.getKey().getItem();
            long got = 0;
            int wanted = 0;
            for (Slot slot : menu.slots) {
                if (topSourceSlot(slot) && ItemStack.isSameItemSameTags(slot.getItem(), in)) got += slot.getItem().getCount();
            }
            for (Map.Entry<Slot, Integer> o : perSet.entrySet()) {
                if (ItemStack.isSameItemSameTags(o.getKey().getItem(), in)) wanted += o.getValue();
            }
            long covers = 1 + got / Math.max(1, wanted);
            if (covers < sets) {
                sets = covers;
                topUpWhy = "stock of " + RecipeData.idOf(in) + " covers only " + covers + " more set" + (covers == 1 ? "" : "s")
                    + " (grid: " + got + " in the bag, " + wanted + " per set)";
            }
            // The numbers behind the fill count, so a fill that comes out too small can be
            // tied to the item that is short (or to a stack the grid cannot hold any more of)
            have.append(' ').append(RecipeData.idOf(in)).append(' ').append(got).append(" in the bag, ").append(wanted).append(" per set");
        }
        // The one shift-click the click loop sends repeats its craft inside the click until the grid runs out, and
        // vanilla drops a result at the player's feet when the inventory has nowhere to put it (the index-0 branch of
        // CraftingMenu.quickMoveStack, player.drop), so the fill is clamped by the room the inventory has for the
        // result; with no room at all the click step's room check stops the run first.
        Slot topResult = resultSlot(menu);
        if (topResult != null && topResult.hasItem()) {
            int room = stackRoom(topResult.getItem(), (int) Math.min(sets, Integer.MAX_VALUE));
            if (room < sets) {
                sets = room;
                topUpWhy = "the inventory has room for " + room + " more of the result";
            }
        }
        if (sets < 2) {
            // The silent one-craft-at-a-time path, named
            NeiAddon.LOGGER.info("Autocrafting: {} has a tool but the grid is only filled for {} crafts (the tool holds {},{}; {} crafts are left to craft)",
                current, sets, holds(toolCrafts), have, walker.remaining());
            if (topUpWhy == null) topUpWhy = "the grid is only filled for " + sets + " more craft" + (sets == 1 ? "" : "s");
            return false;
        }
        topSlots.clear();
        topTarget.clear();
        topPlanTarget.clear();
        topMinItems = Integer.MAX_VALUE;
        for (Slot slot : gridSlots) {
            Integer per = perSet.get(slot);
            if (per == null) continue;
            int want = (int) (per * sets);
            topSlots.add(slot);
            topTarget.put(slot, want);
            topPlanTarget.put(slot, want);
            topMinItems = Math.min(topMinItems, want);
        }
        topIndex = 0;
        topSource = null;
        topSynced = false;
        awaiting = false;
        waited = 0;
        // The fill armed: the visit is not a one-set-a-time visit, no note owed
        topUpWhy = null;
        phase = Phase.TOPUP;
        NeiAddon.LOGGER.info("Autocrafting: {} has a tool, filling the grid for {} crafts (the tool holds {},{})", current, sets, holds(toolCrafts), have);
        return true;
    }

    /**
     * The amounts the current top-up's fill planned, by grid slot. A fill that came up
     * short on the server is retried towards these amounts: the per-set amounts cannot be read off the partly filled
     * grid again, they would stand for their items per set times too many and fill the grid past what is wanted.
     */
    private static final Map<Slot, Integer> topPlanTarget = new HashMap<>();
    /** What the smallest filled grid slot holds when the top-up's fill is done, in items: both the short-fill
     *  check's measure and the log. */
    private static int topMinItems;
    /** The one extra fill round a short fill gets per visit. */
    private static boolean topRetried;

    /** How many crafts a tool holds left, for the log: says so when it does not wear. */
    private static String holds(long left) {
        return left == Long.MAX_VALUE ? "no wear limit" : Long.toString(left);
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
            // the screen is the server's again: craft what the grid really holds in checked steps
            // (see startClicks); when the wear estimate of the tools gives a number, the clicks are clamped by it too
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
            // What the server's screen really holds after the fill clicks, so a fill that stopped short of the plan
            // (or short of what this side clicked) is visible in the log. Both numbers are items in the smallest
            // filled grid slot: a full fill leaves the planned amount in every one of them.
            long serverItems = gridSets();
            if (serverItems < topMinItems) {
                NeiAddon.LOGGER.info("Autocrafting: the fill of {} ended short on the server: {} in the smallest grid slot, {} planned (grid:{})",
                    current, serverItems, topMinItems, describeGrid());
                // One more fill round, towards the amounts the fill had planned (not new ones read off the partly
                // filled grid: see topPlanTarget). The fill clicks bring each slot up to its target from wherever the
                // grid stands now, and every count below is still taken from the server's grid. One retry per visit:
                // when it still comes up short, craft what the grid holds and let the walker go around again (it has
                // this recipe left to do).
                if (!topRetried && !topPlanTarget.isEmpty()) {
                    topRetried = true;
                    topSlots.clear();
                    topTarget.clear();
                    for (Slot slot : gridSlots) {
                        Integer want = topPlanTarget.get(slot);
                        if (want == null) continue;
                        topSlots.add(slot);
                        topTarget.put(slot, want);
                    }
                    topIndex = 0;
                    topSource = null;
                    topSynced = false;
                    awaiting = false;
                    waited = 0;
                    return;
                }
            }
            topRetried = false;
            NeiAddon.LOGGER.info("Autocrafting: the server's grid before the craft of {}: {} ({} in the smallest slot, {} planned)", current, describeGrid(), serverItems, topMinItems);
            // The grid is the server's own; craft it in checked steps (see startClicks), each credited from the
            // server's answer.
            startClicks(mc, result);
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

    /**
     * The chain is done as far as it goes: every recipe that got fewer crafts than the chain wanted of it is named in chat
     * with its count and why it fell short (what the target lacks now, else the reason the run gave up on it, else that it
     * ran out of ingredients). Recipes the tool line or the give-up line already named are left out; nothing is said when
     * every recipe got what it wanted.
     */
    private static void reportUnfinished(Minecraft mc) {
        List<String> parts = new ArrayList<>();
        int more = 0;
        for (Map.Entry<String, Long> e : walker.stillWanted().entrySet()) {
            // in the order the chain first handed them out; the value is what the last pass still wanted, so the
            // recipe's whole want is what it crafted plus that
            String recipeId = e.getKey();
            long crafted = craftedBy.getOrDefault(recipeId, 0L);
            long want = crafted + e.getValue();
            if (skipped.containsKey(recipeId) || givenUp.contains(recipeId) || toolNamed.contains(recipeId)) continue;
            if (parts.size() >= 3) {
                more++;
                continue;
            }
            RecipeLookup.Target<?> target = target(recipeId);
            String missing = ae2 ? ae2Missing(recipeId) : null;
            if (ae2 && missing == null) missing = ae2MissingTool(recipeId);
            String why = missing != null ? missing + " is not in the inventory, the network or the grid"
                : target == null ? "recipe not found" : target.whyNot(menu, mc.player);
            if (why == null) why = giveUpWhy.getOrDefault(recipeId, "ran out of ingredients");
            parts.add(recipeName(recipeId) + ": crafted " + crafted + " of " + want + " (" + why + ")");
        }
        String rest = more > 0 ? " | and " + more + " more" : "";
        NeiAddon.LOGGER.info("Autocrafting finished{}", parts.isEmpty() ? "" : "; came up short: " + String.join(" | ", parts) + rest);
        if (!parts.isEmpty() && mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component
            .literal("§6[NEI Features] §eAutocrafting finished, but these recipes came up short: " + String.join(" | ", parts) + rest + "."));
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
        // The visit's crafts, for the one-set-a-time note (see startTopUp)
        visitCrafts += n;
    }

    /** Gives up on the visit of a recipe in the walker, and keeps the short reason for the end-of-run line (see {@link #reportUnfinished}). */
    private static void abandonVisit(String recipeId, String why) {
        giveUpWhy.put(recipeId, why);
        walker.abandon();
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
     * Per recipe: how many turns the visit was given back this run (see
     * {@link #deferVisit}); separate from AE2's {@code ae2Retried}, which is left alone.
     */
    private static final Map<String, Integer> deferred = new HashMap<>();
    /** How many deferred turns one recipe gets in one run. */
    private static final int DEFERRED_TURNS = 2;

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
