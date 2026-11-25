package neiaddon.autocraft;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import neiaddon.AddonConfig;
import neiaddon.Ae2Crafting;
import neiaddon.Calculator;
import neiaddon.Craft;
import neiaddon.CraftServer;
import neiaddon.CraftSlots;
import neiaddon.FluidStacks;
import neiaddon.McCompat;
import neiaddon.NeiAddon;
import neiaddon.RecipeData;
import neiaddon.RecipeLookup;
import neiaddon.chain.ChainAutocraft;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainMath;
import neiaddon.event.ClientTickEvent;
import neiaddon.platform.ClientPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;

import static neiaddon.autocraft.Ae2Path.*;
import static neiaddon.autocraft.Cleanup.*;
import static neiaddon.autocraft.ClickPath.*;
import static neiaddon.autocraft.Counting.*;
import static neiaddon.autocraft.Run.*;
import static neiaddon.autocraft.ServerPath.*;
import static neiaddon.autocraft.ToolCells.*;
import static neiaddon.autocraft.Visits.*;

/**
 * Starts and stops a run and drives the phase machine, one step per client tick. The phases that need a lot of
 * code hand over to the class that owns them.
 */
public final class Runner {

    /** The open screen's menu when it has a crafting result slot (inventory, crafting table...), else null. */
    static AbstractContainerMenu craftingMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || Calculator.runtime == null || !(McCompat.screen() instanceof AbstractContainerScreen<?> screen)) return null;
        AbstractContainerMenu open = screen.getMenu();
        return resultSlot(open) != null && mc.player.containerMenu == open ? open : null;
    }

    public static boolean available() {
        return AddonConfig.AUTOCRAFTING.get() && craftingMenu() != null;
    }

    static Slot resultSlot(AbstractContainerMenu m) {
        return CraftSlots.result(m);
    }

    /**
     * What the player can craft with: every slot of the open menu except the result, and the grid of a crafting upgrade
     * (Sophisticated Storage keeps it outside the menu's slot list). NEI's getInventoryItems.
     */
    static Map<String, Long> inventory() {
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
        addLecternStored(menu, items);
        return items;
    }

    /** Logged once: the lectern's storage could not be counted. */
    private static boolean lecternStoredWarned;

    /**
     * Adds the items of Ars Nouveau's crafting lectern storage, the selected tab's, which the server's fill and the
     * lectern's own JEI transfer take from. They are not among the menu's slots, so they are read from the menu's
     * {@code itemList} by reflection. A failure counts nothing and is logged once.
     */
    static void addLecternStored(AbstractContainerMenu menu, Map<String, Long> items) {
        if (!CraftServer.isLectern(menu)) return;
        try {
            if (!(menu.getClass().getField("itemList").get(menu) instanceof List<?> stored)) return;
            Method getStack = null;
            Method getQuantity = null;
            // counted apart first, so a failure halfway adds nothing
            Map<String, Long> counted = new HashMap<>();
            for (Object entry : stored) {
                if (getStack == null) {
                    getStack = entry.getClass().getMethod("getStack");
                    getQuantity = entry.getClass().getMethod("getQuantity");
                }
                ItemStack stack = (ItemStack) getStack.invoke(entry);
                long quantity = (Long) getQuantity.invoke(entry);
                if (!stack.isEmpty() && quantity > 0) counted.merge(RecipeData.idOf(stack), quantity, Long::sum);
            }
            counted.forEach((id, n) -> items.merge(id, n, Long::sum));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!lecternStoredWarned) {
                lecternStoredWarned = true;
                NeiAddon.LOGGER.warn("Could not count the items of the Ars Nouveau crafting lectern's storage", e);
            }
        }
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
        // the server path crafts at a refilling upgrade too (the server counts its crafts). Clicks cannot count them,
        // so without the server's help the run is refused as it always was
        boolean vanillaGrid = resultSlot(open) instanceof net.minecraft.world.inventory.ResultSlot
            && (open.getClass() == net.minecraft.world.inventory.CraftingMenu.class
                || open.getClass() == net.minecraft.world.inventory.InventoryMenu.class);
        boolean onServer = (Ae2Crafting.isTerminal(open) || vanillaGrid || CraftServer.isStation(open)
            || CraftSlots.hasCraftingUpgrade(open) || CraftServer.isLectern(open))
            && ClientPlatform.get().canSendToServer(Craft.CraftRequest.TYPE);
        if (!onServer && CraftSlots.refillsGrid(open)) {
            // the upgrade puts new ingredients into the grid after every craft: the crafts could not be counted, and a
            // shift-click would go on crafting from the storage
            NeiAddon.LOGGER.info("Autocrafting not started: the crafting upgrade refills its grid from the storage");
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(REFILL_CHAT));
            }
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
        // a vanilla table or inventory grid, Tinkers' Crafting Station (by name, with no side inventory), an AE2
        // crafting terminal, a Sophisticated crafting upgrade or Ars Nouveau's crafting lectern (see onServer above).
        // An older server answers UNSUPPORTED_MENU to the station, the terminal, the upgrade and the lectern, and the
        // run then goes on by clicks (for AE2 through the store and the click path; with the upgrade refilling its
        // grid, the run stops, see ServerPath).
        serverCraft = onServer;
        craftPending = 0;
        craftSent = null;
        craftAnswer = null;
        craftBusyTries = 0;
        craftBusyWait = 0;
        NeiAddon.LOGGER.info("Autocrafting: crafting {}", serverCraft ? "on the server" : "by clicks");
        ae2Cleared = false;
        gridSlots = craftingGrid(open);
        snapshotLayout();
        clearStorage();
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
        resetChain();
        ChainAutocraft.Host live = new ChainAutocraft.Host() {

            @Override
            public Map<String, Long> inventory() {
                return Runner.inventory();
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
                    Map<String, Long> have = Runner.inventory();
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
        };
        liveHost = live;
        walker = new ChainAutocraft(math, live);
        // the planner is built like the walker (same inventory, same host); it walks the chain once, at the first pick
        planner = new ChainAutocraft(math, live);
        viewFetched = false;
        pendingVisit = null;
        // first make this side's view of the screen the server's (an item seen here but gone there makes JEI's transfer
        // fail). The server path crafts from the server's own inventory, so it starts without this fetch; the storage
        // snapshot that the fetch would have taken is still taken here.
        if (serverCraft) {
            if (!storageTaken) snapshotStorage();
            phase = Phase.NEXT;
            NeiAddon.LOGGER.info("Autocrafting: the screen fetch at the start is skipped (crafting on the server)");
        } else {
            phase = Phase.SYNC;
        }
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
        ae2StoreRefused.clear();
        storeRecipe = null;
        storeSlots.clear();
        storeAt = 0;
        storeSlot = null;
        storeBefore = ItemStack.EMPTY;
        storeStored = 0;
        storeWait = 0;
        ae2Retried.clear();
        ae2RetryWait = 0;
        ae2RefillWait = 0;
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
        ae2RefillWait = 0;
        storeRecipe = null;
        storeSlots.clear();
        storeAt = 0;
        storeSlot = null;
        storeBefore = ItemStack.EMPTY;
        storeStored = 0;
        storeWait = 0;
        ae2StoreRefused.clear();
        walker = null;
        math = null;
        menu = null;
        ae2 = false;
        serverCraft = false;
        craftPending = 0;
        craftSent = null;
        craftAnswer = null;
        pendingVisit = null;
        ae2Carried = ItemStack.EMPTY;
        resetChain();
    }

    /** NEI's autocraftingIgnoreInventory: wanted items the inventory already covers are asked for once more on top. */
    static void ignoreInventory() {
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

    static RecipeLookup.Target<?> target(String recipeId) {
        ItemStack hint = ItemStack.EMPTY;
        for (ChainItem item : math.recipeResults) {
            if (recipeId.equals(item.recipeId)) {
                hint = FluidStacks.stackOf(item.item);
                break;
            }
        }
        return RecipeLookup.findTarget(recipeId, hint);
    }

    public static void onTick(ClientTickEvent.Post event) {
        if (walker == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || craftingMenu() != menu) {
            stop();
            return;
        }
        Slot result = resultSlot(menu);
        Speed.reset();
        // a finished craft goes on to the next transfer in the same tick: the server has answered, nothing to wait for
        for (int steps = 0; steps < 3 && walker != null; steps++) {
            Phase was = phase;
            int sentBefore = sendCount;
            switch (phase) {
                case NEXT -> {
                    if (nextPhase(mc)) return;
                }
                case ADD_TOOL -> {
                    if (addToolPhase(mc)) return;
                }
                case GRID_CLEAR -> {
                    if (gridClearPhase(mc)) return;
                }
                case TRIM -> {
                    RecipeLookup.Target<?> target = target(current);
                    full = false;
                    sendCount++;
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
                    if (waitResultPhase(mc, result)) return;
                }
                case WAIT_ONE_SET -> {
                    if (result.hasItem() && gridSets() == 1) {
                        if (wrongResult(result)) return;
                        // Autocrafting Speed: waits for a tick with budget left
                        if (!Speed.fits(Math.max(1, gridSets()))) return;
                        craft(mc, result);
                    } else if (++waited > TIMEOUT) {
                        NeiAddon.LOGGER.info("Autocrafting: the grid did not go back to one set, giving up on this recipe");
                        abandonVisit(current, "the grid did not go back to one set");
                        phase = Phase.NEXT;
                    }
                }
                case WAIT_CLEAR -> {
                    if (waitClearPhase(mc, result)) return;
                }
                case CLICKS -> clicks(mc, result);
                case SERVER_CRAFT -> serverCraftStep(mc);
                case SERVER_CHAIN -> serverChainStep(mc);
                case CLEANUP -> cleanup(mc);
                case GIVE_BACK -> giveBack(mc);
                case RESTORE -> restore(mc);
                case TOPUP -> topUp(mc, result);
                case AE2_CLEAR -> ae2Clear(mc);
                case AE2_STORE -> storeStep(mc);
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
                    if (syncPhase(mc)) return;
                }
            }
            // WAIT_CLEAR -> NEXT goes on as it always did. A step that waited for the server's answer (ANSWER_WAITS)
            // also goes on when it changed the phase and sent nothing: the answer it waited for is in, and the next
            // step may send. The AE2 path keeps the old rule.
            boolean wentOn = (was == Phase.WAIT_CLEAR && phase == Phase.NEXT)
                || (!ae2 && ANSWER_WAITS.contains(was) && phase != was && sendCount == sentBefore);
            if (!wentOn) break;
        }
    }

    /** The phases that wait for the server's answer, see {@link #onTick}. */
    static final EnumSet<Phase> ANSWER_WAITS = EnumSet.of(Phase.SERVER_CRAFT, Phase.SERVER_CHAIN, Phase.SYNC);

    /**
     * The NEXT phase of {@link Runner#onTick}: picks the next recipe of the chain and starts its visit.
     * Returns true where the original case body returned, so the tick ends.
     */
    static boolean nextPhase(Minecraft mc) {
        if (ae2RetryWait > 0) {
            ae2RetryWait--;
            return true;
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
        // A server-path run skipped the start's fetch: a grid this side shows as filled is emptied
        // from the server's screen
        if (serverCraft && !viewFetched && gridHasItems()) {
            awaiting = false;
            waited = 0;
            resumeAfterSync = Phase.NEXT;
            phase = Phase.SYNC;
            return true;
        }
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
                    return true;
                }
            }
        }
        // The visit was picked before the server's screen was fetched for its fill (see the end of this method)
        if (pendingVisit != null) {
            String pending = pendingVisit;
            pendingVisit = null;
            // AE2 stores the inventory's ingredients in the network before its fill, as a visit without a pending
            // fetch does
            if (ae2 && storeFromInventory(pending)) return true;
            transferVisit(mc, pending);
            return false;
        }
        // Autocrafting Speed: a pick is only made on a tick that may still ask for a craft, so the walker never
        // hands out a visit that has to wait
        if (serverCraft && !Speed.fits(1)) return true;
        // The server path crafts the run's chain in requests (ServerPath.chainStep); the walker's own picks follow it
        if (serverCraft && chainStep()) return true;
        String recipeId = walker.next();
        if (recipeId == null) {
            // done: whatever is still on the cursor or in the grid goes back into the inventory
            reportUnfinished(mc);
            startCleanup();
            return true;
        }
        dumpPlanOnce();
        // What this visit still has to craft, per recipe
        NeiAddon.LOGGER.info("Autocrafting visit: {} still wants {} crafts", recipeId, walker.remaining());
        // The server does the whole visit when it can: a vanilla recipe at a vanilla grid, or at an AE2 terminal (the
        // server fills it from the network and the inventory itself, so no store is needed there)
        if (serverCraft) {
            CraftingRecipe vanilla = vanillaCrafting(mc, recipeId);
            if (vanilla != null) {
                sendServerCraft(recipeId, vanilla);
                return true;
            }
            // no vanilla recipe for this visit: clicks follow, and with a refilling upgrade they cannot count its crafts
            if (CraftSlots.refillsGrid(menu)) {
                clicksAfterServer(mc);
                return true;
            }
        }
        // A server-path run that reaches the click path before any fetch fetches the server's screen first; the visit
        // is kept and its fill goes on once the fetch is done (the pendingVisit check above)
        if (serverCraft && !viewFetched) {
            pendingVisit = recipeId;
            awaiting = false;
            waited = 0;
            resumeAfterSync = Phase.NEXT;
            phase = Phase.SYNC;
            return true;
        }
        // AE2: the ingredients the inventory holds go into the network before the fill, so AE2 can refill
        // the grid from the network after each craft (see storeFromInventory). The fill follows the store.
        if (ae2 && storeFromInventory(recipeId)) return true;
        transferVisit(mc, recipeId);
        return false;
    }

    /**
     * Writes the plan dump once per run. The "give missing" side and the crafting side can disagree on how many crafts
     * a GregTech chain wants (one run planned ~144 plate crafts, far over the tooltip's plan, and ate the whole
     * titanium supply, starving the ring/rotor/vent steps). The plan the walker starts from is dumped so the log can be
     * compared with the "Gave the missing ingredients" line (join on the item id) and with the visit/count lines (join
     * on the recipe id). The maths is only filled in inside a walker.next() call, which is why the dump follows the
     * first one.
     */
    static void dumpPlanOnce() {
        if (planDumped) return;
        planDumped = true;
        for (Map.Entry<ChainItem, Long> e : math.requiredAmount.entrySet()) {
            if (e.getValue() <= 0) continue;
            NeiAddon.LOGGER.info("Autocrafting plan: {} x{} of {} (wanted by recipe {})",
                e.getKey().type, e.getValue(), e.getKey().item, e.getKey().recipeId);
        }
    }

    static boolean gridHasItems() {
        for (Slot slot : gridSlots) {
            if (slot.hasItem()) return true;
        }
        return false;
    }
}
