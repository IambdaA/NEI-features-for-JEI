package neiaddon.autocraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import neiaddon.Craft;
import neiaddon.chain.ChainAutocraft;
import neiaddon.chain.ChainMath;
import neiaddon.chain.ChainPredict;
import neiaddon.chain.CraftCount;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * The state of one autocrafting run: the phase, the counters, the snapshots and the constants. Every other class
 * in this package reads and writes these static fields. A run is one chain at one open crafting screen, and
 * {@link Runner#start} resets what has to be reset.
 */
final class Run {

    /** Ticks to wait for the server before giving up on a craft. */
    static final int TIMEOUT = 40;

    enum Phase {
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
        AE2_CLEAR,
        /** AE2: the recipe's ingredients the inventory holds are shift-clicked into the network, before the fill. */
        AE2_STORE,
        /** The server crafts the visit's recipe by itself; this waits for its answer (see {@link ServerPath#sendServerCraft}). */
        SERVER_CRAFT,
        /**
         * The server crafts the run's chain in requests; this waits for an answer (see {@link ServerPath#sendChain}).
         */
        SERVER_CHAIN
    }

    /** What {@link ToolCells#addMissingTool} did about the recipe's tools. */
    enum ToolFill {
        /** Nothing was missing: the recipe wants no tool, or the grid already holds each one. */
        NOT_NEEDED,
        /** Every missing tool was clicked into the grid (optimistic clicks: the screen must be synced again). */
        PLACED,
        /** A tool is in neither the grid nor any reachable slot; no click was sent. */
        REFUSED,
        /** A tool is in neither the grid nor any reachable slot, after some tools were already clicked in. */
        REFUSED_AFTER_CLICKS
    }

    static ChainAutocraft walker;

    static ChainMath math;

    static AbstractContainerMenu menu;

    static Phase phase = Phase.NEXT;

    static int waited;

    static String current;

    /** Whether the last transfer asked for as many sets as fit. */
    static boolean full;

    /**
     * The recipes whose grid was already fetched from the server before their first batch craft in this run: the
     * batch's sets, its clicks and their credit all come from that screen (see the {@link Phase#WAIT_RESULT} case).
     * The sync is once per recipe BATCH, even though the batch itself crafts in checked steps, each credited
     * from the server's answer.
     */
    static final java.util.Set<String> batchSynced = new java.util.HashSet<>();

    /**
     * Where the {@link Phase#SYNC} case goes on once the server's screen has arrived: {@link Phase#NEXT} (the transfer
     * is issued again), or back to {@link Phase#WAIT_RESULT} when the sync runs before the first craft of a batch, so
     * its sets and its craft count are read from the server's own screen, or to {@link Phase#ADD_TOOL} after the fill
     * of a recipe with a tool ingredient, so the tool goes into the server's grid.
     */
    static Phase resumeAfterSync = Phase.NEXT;

    /**
     * Where the {@link Phase#GRID_CLEAR} case goes on once the grid holds nothing this side can move out:
     * {@link Phase#NEXT} (the next transfer then starts from an empty grid), or {@link Phase#TRIM} when the grid is
     * emptied so the tail of the current recipe can be re-filled with one set.
     */
    static Phase resumeAfterFill = Phase.NEXT;

    /**
     * Grid slots the {@link Phase#GRID_CLEAR} case already shift-clicked (a slot that does not empty, an inventory
     * without room, is not tried again).
     */
    static final java.util.Set<Slot> fillCleared = new java.util.HashSet<>();

    /**
     * How many {@link Phase#GRID_CLEAR} rounds in a row found the grid still filled after trying every filled slot once
     * (an inventory without room). After {@link #GRID_CLEAR_ROUNDS} the fill is issued on top of the items still in the
     * grid (bounded, so a grid that cannot be emptied cannot loop); it resets when a round empties the grid.
     */
    static int fillClearTries;

    static final int GRID_CLEAR_ROUNDS = 2;

    /**
     * Whether the plan dump (see the {@link Phase#NEXT} case) was already written this run. The maths only knows the
     * plan after the first {@code walker.next()} refreshes it, so the dump runs there, once.
     */
    static boolean planDumped;

    /**
     * Per recipe, how often in a row its batch shift-click crafted nothing on the server (see
     * {@link Counting#craftsDoneBatch()}); at {@link CraftCount#BATCH_ZERO_TRIES} the recipe is given up on instead of retried
     * forever ({@link CraftCount#giveUp(int)}).
     */
    static final Map<String, Integer> batchZeroCrafts = new HashMap<>();

    static List<Slot> gridSlots = List.of();

    static final Map<Slot, ItemStack> before = new HashMap<>();

    /** The menu's state id sent with our last click; the server's answer replaces it (see forceAnswer). */
    static int clickState;

    /** Batches: crafts still to make by clicking the result, and whether a click waits for the server. */
    static long clicksLeft;

    /** What one batch of result clicks has crafted so far, for the count line in the log. */
    static long clicksMade;

    /** Whether this batch's grid held a tool: once the tool breaks its slot is empty, so the click
     *  loop cannot tell a wear-out from an unmeasurable grid without remembering that the grid had a tool. */
    static boolean hadTool;

    /**
     * How many result clicks one step of the click loop sends at once. They are shift-clicks, so the crafted items go
     * straight into the player's inventory (see {@link ClickPath#clicks}). Recipes the WAIT_CLEAR recovery pinned in
     * {@link #singleClicks} never burst: they always click once onto the cursor. A burst is what a player's click-spam
     * on a crafting table sends. Every vanilla click first crafts on this side's model of the screen (the vanilla
     * shift-click simulation ends in the same {@code ResultSlot} consumption code the server runs for its copy of the
     * click), and then sends that guess to the server, which processes every click it gets in order.
     * {@link ClickPath#forceAnswer()} makes each click of the burst carry a stale state id, so the server answers each one with
     * a full copy of the screen (a stale id is only a flag there, never a refusal). The burst is measured from the grid
     * after the answers: it must show exactly as many sets lost as the burst sent. A credit that measures short waits
     * for the answers to stop coming and measures the same grid again before it is believed (see {@link #settling}). 4
     * is also what GTNH's own NEI throttle branch batches its crafts by.
     */
    static final int CLICK_BURST = 4;

    /** The clicks the outstanding burst sent; its answer must show exactly that many
     *  grid sets consumed, and when the grid shows fewer only those are credited. */
    static int clicksSent;

    /** Whether the credit window now open was opened by the one shift-click step: vanilla's repeat loop inside that
     *  click crafts up to the whole grid in one go, and the first answer that advances the state id can lag the
     *  craft's own per-slot changes. Without a re-measure, a lagging answer would credit 1..6 of grids holding 32..64
     *  whole sets that the click had already consumed, so only this kind of step re-measures the grid once it goes
     *  quiet before believing it crafted less than the snapshot's sets. */
    static boolean shiftStep;

    /** Whether this visit of the click loop may still click in a burst: a burst that
     *  consumed less than it sent falls back to one click per step for the rest of the visit. */
    static boolean burstHere = true;

    /** The answers to a burst come back one per click. They usually all land in one client tick, but a server tick can
     *  split them over two. A burst that measured short may therefore be only the late answers. The credit does not
     *  take the short count then, because the server already crafted the rest and the walker would craft those sets a
     *  second time. It waits here until the state id stops moving, then measures the same grid against the same
     *  snapshot once more ({@link #settleId} holds the last seen id). */
    static boolean settling;

    static int settleId, settleQuiet, settleMoves;

    /** Whether this visit already fetched the server's screen once after the result slot read empty on a grid that
     *  still holds sets: the empty read was this side's stale mirror, not a real empty (cursor picks that fill the
     *  inventory's last free space make the mirror read empty while the server's answer keeps every set). One re-read
     *  per visit, then the other paths decide from the server's own screen. */
    static boolean giveUpSynced;

    /** The last forced screen sync scheduled for the recipe being worked on
     *  could not complete: {@link ClickPath#syncClick} found no empty slot, or its answer never came: this side's mirror was
     *  never verified against the server, so this recipe's clicks stay one per step, because a burst's guessed moves
     *  are only safe to send from a mirror the server has just confirmed. Every scheduled sync re-decides the flag. */
    static boolean syncUnverified;

    static boolean awaiting, lastWasCraft;

    /**
     * An AE2 crafting terminal is open (see {@link Ae2Crafting}): the network counts as inventory, the grid fills from it
     * one set at a time, each craft is one AE2 craft action onto the cursor (AE2 refills the grid from the network, so a
     * craft is counted by the cursor, not by the grid), and the cleanup puts the grid back into the network.
     */
    static boolean ae2;

    /**
     * Whether this run lets the server do each visit's crafts (see {@link CraftServer}): a vanilla crafting table or
     * inventory grid, Tinkers' Crafting Station (without a side inventory), or an AE2 crafting terminal (the visits
     * the server crafts skip {@link Ae2Path}'s store), the addon on the server, and no earlier refusal in this run.
     * Off, the clicks below do the work.
     */
    static boolean serverCraft;

    /**
     * How many packets and clicks this run has sent to the server (the clicks, JEI transfers and craft requests of
     * the click and server paths). {@link Runner#onTick} compares it before and after one step: a step that sent
     * nothing may hand over to the next step in the same tick.
     */
    static int sendCount;

    /**
     * Whether a {@link Phase#SYNC} has fetched this run's screen from the server. A server-path run starts without that
     * fetch (see {@link Runner#start}), so its first visit that needs the click path fetches the screen first.
     */
    static boolean viewFetched;

    /**
     * The visit that {@link Runner#nextPhase} picked while the screen was fetched for it (the server path's first click
     * visit): its fill goes on from {@link Runner#nextPhase} once the fetch is done.
     */
    static String pendingVisit;

    /** Ticks to wait for the server's answer to a craft request before the run goes back to clicking. */
    static final int SERVER_CRAFT_TIMEOUT = 200;

    /** How often in a row a request is sent again after the server said it was busy, and the ticks between them. */
    static final int SERVER_CRAFT_BUSY_TRIES = 5;

    static final int SERVER_CRAFT_BUSY_WAIT = 2;

    /** The id the next craft request gets, the id being waited for (0: none), and the request itself for a resend. */
    static int craftRequestId;

    static int craftPending;

    static Craft.CraftRequest craftSent;

    /** The newest answer, written on the client thread by the loader's receiver and read by the tick. */
    static volatile Craft.CraftResult craftAnswer;

    static int craftWaited, craftBusyTries, craftBusyWait;

    /** One piece of the run's chain as the server gets it: the recipe's vanilla id and the items chosen for it. */
    record ChainPiece(String recipe, Identifier vanilla, int times, List<Identifier> preferred) {}

    /**
     * The run's chain, predicted once at its first pick (null until then, see {@link ServerPath#chainStep}): the pieces
     * in order. {@link #chainAt} is the first piece not yet answered; a request carries the pieces from there on, up to
     * the request limits.
     */
    static List<ChainPiece> chainPlan;

    static int chainAt;

    /** The pieces of the chain request in flight (the answer's entries follow their order), and the packet. */
    static List<ChainPiece> chainSent = List.of();

    static Craft.CraftChainRequest chainRequest;

    /** The newest chain answer, written on the client thread by the loader's receiver and read by the tick. */
    static volatile Craft.CraftChainResult chainAnswer;

    /** The walker's host while a chain answer is replayed: the predicted counts, fed the crafts the server made. */
    static ChainPredict.SimHost chainReplayHost;

    /** The walker's twin, which walks the predicted chain once (see {@link ChainPredict#plan}). */
    static ChainAutocraft planner;

    /** The host the walker asks the real game, as it is between replays. */
    static ChainAutocraft.Host liveHost;

    /** AE2: the cursor before the last craft action, the result count it should add, and whether the grid was cleared. */
    static ItemStack ae2Carried = ItemStack.EMPTY;

    static int ae2Adds;

    /** AE2: how many crafts the last craft action could make (1, or a stack's worth for AE2's stack craft). */
    static int ae2Crafts = 1;

    static boolean ae2Cleared;

    /** Grid slots the cleanup already shift-clicked (a slot that does not empty is not tried again). */
    static final java.util.Set<Slot> cleaned = new java.util.HashSet<>();

    /**
     * AE2: recipes skipped in this run because the grid made another item, mapped to what the end of the run says about
     * them. A skipped recipe is not tried again in the run.
     */
    static final Map<String, String> skipped = new java.util.LinkedHashMap<>();

    /** AE2: recipes whose missing ingredient was already logged in this run. */
    static final java.util.Set<String> ae2Logged = new java.util.HashSet<>();

    /** Recipes whose give-up of a click batch was already said in chat in this run: the
     *  walker hands a given-up recipe again on a later pass, and the retry may well work once a new tool is crafted. */
    static final java.util.Set<String> givenUp = new java.util.HashSet<>();

    /** Recipes whose missing tool was already named in chat in this run (see {@link ToolCells#addMissingTool}). */
    static final java.util.Set<String> toolNamed = new java.util.HashSet<>();

    /** Per recipe: the short reason the run gave up on its visit last (see {@link Visits#abandonVisit}), for the end-of-run line. */
    static final Map<String, String> giveUpWhy = new HashMap<>();

    /** Recipes whose click loop already took the "the result slot read empty with crafts still left, fill the grid
     *  afresh instead of giving up" detour once this run; the second time the other give-up paths decide. Cleared with
     *  every run. */
    static final java.util.Set<String> refills = new java.util.HashSet<>();

    /** AE2: recipes whose last transfer was refused and is asked again after {@link #AE2_RETRY_TICKS}. */
    static final java.util.Set<String> ae2Retried = new java.util.HashSet<>();

    static final int AE2_RETRY_TICKS = 10;

    static int ae2RetryWait;

    /** AE2 refills the grid from the network a tick or two after the cursor shows a craft; the click loop waits this
     *  many ticks for an empty result slot to fill again before it decides the network is out of an ingredient. */
    static final int AE2_REFILL_TICKS = 10;

    static int ae2RefillWait;

    /** AE2 store: the recipe whose inventory ingredients are being stored, its slots to try, and the next one. */
    static String storeRecipe;

    static final List<Slot> storeSlots = new ArrayList<>();

    static int storeAt;

    /** AE2 store: the slot of the last shift-click, the stack it held before, and the stacks stored this visit. */
    static Slot storeSlot;

    static ItemStack storeBefore = ItemStack.EMPTY;

    static int storeStored;

    /** AE2 store: the last state id seen while waiting for a click's answer, and the ticks it has stayed the same. */
    static int storeSeenId, storeQuiet;

    /** AE2 store: ticks waited after the last store, so AE2's list of the network on this side has the stored items
     *  before the fill reads it. */
    static int storeWait;

    static final int AE2_STORE_SETTLE_TICKS = 5;

    /** AE2 store: plain ids the network did not take this run (the shift-click left the stack as it was). */
    static final java.util.Set<String> ae2StoreRefused = new java.util.HashSet<>();

    /** AE2: the cursor's stack is going into the network (see {@link Ae2Path#ae2PutWait}), and how often in a row it was tried. */
    static boolean ae2Putting;

    static int ae2PutTries;

    /** How many times in a row the network is asked to take the cursor's stack before the run stops with it on the cursor. */
    static final int AE2_PUT_TRIES = 3;

    /** AE2 after a skip: the cursor's stack the clear last deposited, and how often in a row it was tried (see {@link Ae2Path#ae2Clear}). */
    static ItemStack ae2ClearCarried = ItemStack.EMPTY;

    static int ae2ClearDeposits;

    /** How many deposits of the same cursor stack the clear makes before it stops (as its timeout does). */
    static final int AE2_CLEAR_DEPOSITS = 3;

    /** Whether the chat line naming the skipped recipes was sent in this run (see {@link Visits#saySkipped}). */
    static boolean skippedSaid;

    /** AE2: whether the last {@link Runner#inventory()} could read the network's items. */
    static boolean networkRead;

    /**
     * Is the result slot an item the recipe being crafted does not make? Another recipe with the same ingredients can
     * take priority in the grid (a mod's duplicate of a vanilla tool), and crafting that would never get the wanted item,
     * so the chain would craft it again and again. Only the item is compared (a result's NBT can vary). When it differs
     * this gives up on the recipe, tells the player and starts the cleanup (AE2: skips the recipe and goes on, see
     * {@link Ae2Path#ae2Skip}); returns true then (do not craft).
     */
    /** Recipes whose wrong-looking result was already checked with the server once, and where to go on after the check. */
    static final java.util.Set<String> rechecked = new java.util.HashSet<>();

    static Phase resumeAfterRecheck = Phase.NEXT;

    /** The grid the last {@link Counting#craftsMeasured()} counted from, for the log. */
    static String measuredNote = "";

    /** The whole sets the grid held at the last {@link Counting#snapshot()}, the ceiling the
     *  burst credit stops at (see {@link ClickPath#creditBurst}). */
    static long snapshotSets;

    /** What the credits of the current click step have already claimed against that
     *  snapshot, zeroed by every {@link Counting#snapshot()}: the empty-result read path compares it with what the grid now
     *  visibly lost, to tell a late mirror from a fill that never ran (see {@link ClickPath#clicks}). */
    static long stepCredit;

    // ---------------------------------------------------------------- leftovers back into the container's storage
    /** Clicks the give-back may make: a bound, so it always ends. */
    static final int RETURN_CLICKS = 200;

    /** Clicks putting the cursor back into the inventory before the give-back gives up on it. */
    static final int BACK_TRIES = 3;

    /**
     * Per item: how many the open container's own storage held when autocrafting started (a crafting station's side
     * chest, a Sophisticated storage). Empty when the container has none.
     */
    static final Map<String, Long> storageStart = new HashMap<>();

    /** Per item: how many the player's main inventory and hotbar held then (only when storageStart is not empty). */
    static final Map<String, Long> playerStart = new HashMap<>();

    /** What each storage slot and main inventory slot held then (only when storageStart is not empty). */
    static final Map<Slot, ItemStack> slotStart = new HashMap<>();

    /** Items the give-back is done with (all owed went back, or the storage took no more). */
    static final java.util.Set<String> givenBack = new java.util.HashSet<>();

    /** The inventory slot the cursor's stack was picked up from, so the rest goes back there. */
    static Slot pickedFrom;

    /** The item the last click picked up (null once the cursor holds it): a pickup that got nothing ends that item. */
    static String pickedUp;

    /** Give-back clicks made, and the cursor's count when the last one into the storage was sent (-1: none since). */
    static int returnClicks, returnCount = -1;

    /** Clicks made to put the cursor back into the inventory since it was last empty. */
    static int backTries;

    /** Whether the snapshot was taken (once per start: a later sync, retrying a recipe, must not take it again). */
    static boolean storageTaken;

    /** Whether the open container has storage slots of its own (set with the snapshot). */
    static boolean hasStorage;

    /** The give-back's second pass: items the storage took from the player, and the asked-for items, go to the player. */
    static boolean reclaiming;

    /**
     * The items this run was asked for: the results of the chain's top recipes (the ingredients of its made-up root
     * recipe), taken when the run starts. The chain's own list of plain items is no use here: each batch refills it with
     * whatever the inventory and storage hold.
     */
    static final java.util.Set<String> askedFor = new java.util.HashSet<>();

    /** AE2: items (plain ids) the network refused this run; they go into the inventory. */
    static final java.util.Set<String> ae2Refused = new java.util.HashSet<>();

    /** Per asked-for item: how many were asked for (the player keeps that many of what the inventory gained). */
    static final Map<String, Long> askedAmount = new HashMap<>();

    /** Items this run crafted on the way to the asked-for items (set when the give-back starts). */
    static final java.util.Set<String> madeOnTheWay = new java.util.HashSet<>();

    /** The storage slots the crafting took items from (set when the give-back starts): where items made on the way go. */
    static final List<Slot> drawnFrom = new ArrayList<>();

    // ---------------------------------------------------------------- Restore Inventory Layout
    /** Clicks the layout restore may make in all (a swap is two or three): a bound, so it always ends. */
    static final int RESTORE_CLICKS = 120;

    /** The main inventory and hotbar when autocrafting started, by inventory index; empty when the setting is off. */
    static final Map<Integer, ItemStack> layout = new HashMap<>();

    /** The clicks of the swap in progress. */
    static final java.util.ArrayDeque<Integer> pending = new java.util.ArrayDeque<>();

    static int restoreNext, restoreClicks;

    // ---------------------------------------------------------------- recipes with a tool: several sets around one tool
    /** Most clicks one top-up may take: a bound, so it always ends. */
    static final int TOPUP_CLICKS = 600;

    /** The grid slots to fill, in order, and how many items each should hold. */
    static final List<Slot> topSlots = new ArrayList<>();

    static final Map<Slot, Integer> topTarget = new HashMap<>();

    static int topIndex;

    /** Where the stack on the cursor was picked up from, to put the rest back there. */
    static Slot topSource;

    /**
     * Why the last {@link Cleanup#startTopUp()} declined, in short: which clamp cut the
     * planned fill (or why there was nothing to top up); null while it has not been asked, or when the answer
     * was yes. The next {@link Cleanup#startTopUp()} call overwrites it, and the visit that ends hands it to the log.
     */
    static String topUpWhy;

    /** Crafts credited to the current recipe visit; the did-not-arm note is only
     *  printed for a visit that crafted exactly one set while the plan still wanted more. */
    static long visitCrafts;

    /**
     * The amounts the current top-up's fill planned, by grid slot. A fill that came up
     * short on the server is retried towards these amounts: the per-set amounts cannot be read off the partly filled
     * grid again, they would stand for their items per set times too many and fill the grid past what is wanted.
     */
    static final Map<Slot, Integer> topPlanTarget = new HashMap<>();

    /** What the smallest filled grid slot holds when the top-up's fill is done, in items: both the short-fill
     *  check's measure and the log. */
    static int topMinItems;

    /** The one extra fill round a short fill gets per visit. */
    static boolean topRetried;

    static boolean topSynced;

    /** Ticks a first transfer may take to show a result before the screen is fetched again and it is retried. */
    static final int FIRST_RESULT_TICKS = 10;

    /** Ticks to wait after the server's answer to a sync click, see the SYNC phase. */
    static final int SETTLE_TICKS = 2;

    static int settle;

    /**
     * The fill's wait after its empty click (see topUp): the id last seen, quiet steps, moves seen, and ticks spent
     * waiting for a late answer once the id is quiet.
     */
    static int topSettleId, topQuiet, topMoves, topLate;

    /** How long topUp waits for a late answer before a cursor or grid that looks wrong fails the fill (5 s). */
    static final int LATE_TICKS = 100;

    /** Crafts done per recipe in this run. */
    static final Map<String, Long> craftedBy = new HashMap<>();

    /** Recipes whose result is taken by single clicks because shift-clicking it did nothing. */
    static final java.util.Set<String> singleClicks = new java.util.HashSet<>();

    /** Per recipe: how often the screen was fetched again after a transfer that did nothing. */
    static final Map<String, Integer> syncRetries = new HashMap<>();

    /**
     * Per recipe: how many turns the visit was given back this run (see
     * {@link Visits#deferVisit}); separate from AE2's {@code ae2Retried}, which is left alone.
     */
    static final Map<String, Integer> deferred = new HashMap<>();

    /** How many deferred turns one recipe gets in one run. */
    static final int DEFERRED_TURNS = 2;
}
