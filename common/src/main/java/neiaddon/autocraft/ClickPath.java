package neiaddon.autocraft;

import neiaddon.FluidStacks;
import neiaddon.NeiAddon;
import neiaddon.RecipeData;
import neiaddon.RecipeLookup;
import neiaddon.Tools;
import neiaddon.chain.ChainItem;
import neiaddon.chain.CraftCount;
import neiaddon.chain.GridMath;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static neiaddon.autocraft.Ae2Path.*;
import static neiaddon.autocraft.Cleanup.*;
import static neiaddon.autocraft.Counting.*;
import static neiaddon.autocraft.Run.*;
import static neiaddon.autocraft.Runner.*;
import static neiaddon.autocraft.ToolCells.*;
import static neiaddon.autocraft.Visits.*;

/**
 * The click path: fills the grid through JEI's transfer, then crafts by shift-clicking the result or by cursor picks,
 * one verified step per tick. Also holds the click helpers every path shares.
 */
final class ClickPath {

    /**
     * The fill of one visit: the recipe's transfer puts the ingredients into the grid, and the phase waits for the
     * result (or fetches the server's screen first). Called from {@link Phase#NEXT}, and from {@link Ae2Path#storeFinish}
     * once the inventory's ingredients are in the AE2 network.
     */
    static void transferVisit(Minecraft mc, String recipeId) {
        // a retry waits for this same recipe: a mark left by a recipe the chain moved on from is stale
        ae2Retried.removeIf(id -> !id.equals(recipeId));
        RecipeLookup.Target<?> target = target(recipeId);
        // batching: as many sets as the grid takes while more than one craft is wanted and the Autocrafting
        // Speed limit (if any) allows a full grid; one set otherwise.
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
        // Autocrafting Speed: a full fill holds up to a stack of sets, so under a limit below 64 the fill is one set
        // and the visit crafts one set per click (a tool recipe's top-up is capped by the limit itself)
        int speed = Speed.limit();
        full = !ae2 && !toolInGrid && !unstackableInput(recipeId) && !gridSlots.isEmpty() && walker.remaining() > 1
            && (speed == 0 || speed >= 64);
        sendCount++;
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

    static boolean wrongResult(Slot result) {
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

    /** Shift-clicks the result: vanilla crafts as long as the grid has a full set, and drops a result at the
     *  player's feet when the inventory has no room for it, so this checks room first. */
    static void craft(Minecraft mc, Slot result) {
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
        sendCount++;
        // one shift-click crafts every set in the grid
        Speed.spend(Math.max(1, gridSets()));
        mc.gameMode.handleContainerInput(menu.containerId, result.index, 0, ContainerInput.QUICK_MOVE, mc.player);
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
    static void startClicks(Minecraft mc, Slot result) {
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
     * Whether the recipe takes an item that stacks to one (a GregTech tool, or any other unstackable item) into a grid
     * slot. Read from the chain's planned ingredients of this recipe, as {@link ToolCells#addMissingTool} reads them, skipping
     * the ingredients with no factor ({@link ChainItem#emptyFactor()}) and the fluids. An ingredient counts when its
     * item or any option it accepts (a tag's members) stacks to one; an item whose stack cannot be read counts as
     * stackable (the batched fill as usual).
     */
    static boolean unstackableInput(String recipeId) {
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor() || isFluid(ingredient)) continue;
            for (String id : ingredient.options) {
                if (unstackable(id)) return true;
            }
        }
        return false;
    }

    /** Whether an item id names an item that stacks to one (a fluid id is never one). */
    static boolean unstackable(String id) {
        if (FluidStacks.isFluidId(id)) return false;
        ItemStack stack = FluidStacks.stackOf(id);
        return !stack.isEmpty() && stack.getMaxStackSize() == 1;
    }

    // ---------------------------------------------------------------- batches: exact counts by clicking
    /**
     * One step of crafting {@link Run#clicksLeft} items from a full grid by shift-clicking the result slot (the crafted
     * stacks go straight into the player's inventory). The step sends one shift-click, because vanilla repeats the
     * craft inside that one click: {@code AbstractContainerMenu.doClick}'s QUICK_MOVE branch loops
     * {@code quickMoveStack} while the result slot keeps yielding the same item, crafting until the grid runs out, so a
     * second shift-click after the grid-draining one is a no-op anyway. The cursor-pick PICKUP fallback
     * ({@link #burstSize} clamp, bursts of up to {@link Run#CLICK_BURST} clicks) still picks one result per click. The
     * answers of the step are its whole measurement: every click carries a stale state id, so the server answers each
     * one with a full copy of its screen, and a burst is credited whole when its grid lost as many sets as it held. A
     * burst that measures fewer than it sent first waits for the answers to stop coming and measures the same grid once
     * more (see {@link Run#settling}); still fewer, and only the measured ones are credited, the loop says so in the log
     * and clicks one at a time for the rest of the visit. The step stops with a clear message when the inventory has no
     * room left for one more result, because a shift-click whose result fits nowhere still crafts and drops the
     * leftover at the player's feet. Recipes the WAIT_CLEAR recovery pinned in {@link Run#singleClicks}, and a grid
     * holding more sets than the inventory has room for result stacks, keep the cursor picks and deposits; the grid's
     * unused sets stay for the next transfer (JEI empties the grid first) or the cleanup. Anything unexpected stops
     * autocrafting through the cleanup.
     */
    static void clicks(Minecraft mc, Slot result) {
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
            // every credit of this step is remembered in {@link Run#stepCredit}, and the mirror can catch up late on
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
            || (room > 0 && gridSets() > clicksLeft) || (Speed.limit() > 0 && gridSets() > Speed.limit());
        if (!cursorPick && !carried.isEmpty()) {
            // The player holds something on the cursor: put it away first (the fits check of the cursor picks does this
            // on its own), so a shift-click burst never starts while this side's cursor and the server's disagree
            deposit(mc, carried);
            return;
        }
        if (cursorPick) {
            boolean fits = carried.isEmpty()
                || ItemStack.isSameItemSameComponents(carried, out) && carried.getCount() + out.getCount() <= carried.getMaxStackSize();
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
        // Autocrafting Speed: this click's crafts must fit what this tick may still ask for. Nothing is in flight here,
        // so the step waits for the next tick, which starts with the whole budget (a shift step never holds more
        // sets than the limit: such a grid takes the cursor picks above).
        long wantedNow = cursorPick ? 1 : Math.max(1, gridSets());
        if (!Speed.fits(wantedNow)) return;
        snapshot();
        // One shift-click per step: vanilla's doClick repeat loop runs the crafts of the whole grid inside that one
        // click, so a second shift-click after the grid-draining one would be a no-op. Only the cursor-pick PICKUP
        // fallback (recipes pinned in singleClicks, and grids demoted above for lack of room) bursts, one pick per
        // click.
        clicksSent = cursorPick ? (int) Math.max(1, Math.min(burstSize(out), Speed.allowed(burstSize(out)))) : 1;
        Speed.spend(cursorPick ? clicksSent : wantedNow);
        shiftStep = !cursorPick;
        send(mc, result.index, !cursorPick);
        // The rest of the burst: the very vanilla click the first send() wraps, sent straight (the state id this side
        // claims is already stale from forceAnswer, so the server will answer every one of them with a full screen) and
        // processed in order. The shift-click path has no rest to send, its repetition is vanilla's loop inside the
        // first click; what is left here are the cursor picks. The credit block at the top measures the step from the
        // grid after the answers, and waits for late answers before believing a short one (see {@link Run#settling})
        for (int i = 1; i < clicksSent; i++) {
            sendCount++;
            mc.gameMode.handleContainerInput(menu.containerId, result.index, 0,
                cursorPick ? ContainerInput.PICKUP : ContainerInput.QUICK_MOVE, mc.player);
        }
        lastWasCraft = true;
    }

    /**
     * Credit the outstanding burst of result clicks against the snapshot taken before it. Only what the grid itself
     * lost counts, and {@link Run#clicksSent} says how many clicks the burst sent. A burst that consumed fewer is credited
     * only the measured crafts, said in the log, and the rest of the visit clicks one at a time. When
     * {@code allowRetry} is set, the burst sent more than one click and the credit has not waited for late answers
     * yet, the credit first waits (see {@link Run#settling}) and this runs again on the same grid. That way an answer that
     * landed in a later tick is folded into the credit, instead of leaving the walker owing crafts the server already
     * made. A burst that consumed nothing, or crafted more times than the snapshot's grid held whole sets, stops the
     * run, with the count line of the crafts credited so far first. Returns true when the step must stop here (the
     * credit waits, or the run stopped).
     */
    static boolean creditBurst(boolean allowRetry) {
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
     * How many result clicks the current PICKUP-fallback step sends: at most {@link Run#CLICK_BURST}, and never more than
     * what is still wanted, what the grid holds in sets, or what its tools are estimated to hold left, and by how many
     * result stacks the inventory can currently absorb (a shift-click that fits nowhere still crafts and drops the
     * result, so the room checks guard the run instead; {@link #clicks()} also checks that the inventory has room for
     * at least one result before it sends any burst). The shift-click step sends one click too (vanilla's repeat loop
     * inside it does the repetition). A step that must not burst (see {@link Run#burstHere}) sends one click.
     */
    static int burstSize(ItemStack out) {
        if (!burstHere) return 1;
        java.util.List<Long> left = new java.util.ArrayList<>();
        for (Slot slot : gridSlots) {
            ItemStack in = slot.getItem();
            if (Tools.isTool(in)) left.add((long) Tools.craftsLeft(in));
        }
        long[] toolCrafts = new long[left.size()];
        for (int i = 0; i < toolCrafts.length; i++) toolCrafts[i] = left.get(i);
        return GridMath.burstSize(gridSets(), stackRoom(out, CLICK_BURST), clicksLeft, toolCrafts, CLICK_BURST);
    }

    /**
     * How many result stacks fit in the player's inventory right now, counted the way vanilla's shift-click move does
     * it (partial merges over the main inventory and hotbar count, the offhand not; see {@link Cleanup#roomFor}). A burst is
     * clamped to this so a nearly full inventory cannot leave clicks of a burst silently doing nothing and turn a
     * healthy burst into a measured-short one. The counting stops at {@code cap}: a burst passes {@link Run#CLICK_BURST},
     * and the tool recipe's batch fill (see {@link Cleanup#startTopUp}) passes the sets it plans to craft, so a fill is
     * clamped by the real room and not by the burst size.
     */
    static int stackRoom(ItemStack out, int cap) {
        java.util.List<Long> rooms = new java.util.ArrayList<>();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= 36 || !slot.mayPlace(out)) continue;
            ItemStack in = slot.getItem();
            int max = Math.min(out.getMaxStackSize(), slot.getMaxStackSize(out));
            rooms.add(GridMath.slotRoom(in.isEmpty(), ItemStack.isSameItemSameComponents(in, out), max, in.getCount()));
        }
        long[] slotRooms = new long[rooms.size()];
        for (int i = 0; i < slotRooms.length; i++) slotRooms[i] = rooms.get(i);
        return GridMath.stackRoom(out.getCount(), cap, slotRooms);
    }

    /** Sends a click (pickup, or shift-click) on a slot of the menu and waits for the server's answer. */
    static void send(Minecraft mc, int slot, boolean quickMove) {
        send(mc, slot, quickMove, 0);
    }

    /** A pickup click that does not wait for the server (filling the grid; the server's screen is fetched once at the end). */
    static void clickNoWait(Minecraft mc, int slot, int button) {
        sendCount++;
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, mc.player);
    }

    /** {@code button} 1 (right click) with an item on the cursor puts one of it into the slot. */
    static void send(Minecraft mc, int slot, boolean quickMove, int button) {
        forceAnswer();
        sendCount++;
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, quickMove ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP, mc.player);
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
    static void forceAnswer() {
        // far ahead: the server's own id may already be a few steps ahead of this side's (updates still on their way), and
        // a click whose id happens to be the server's is not answered when this side guessed it right
        for (int i = 0; i < 1000; i++) menu.incrementStateId();
        clickState = menu.getStateId();
    }

    /** The result of a craft stayed in its slot: says why (in the log, and in chat for the player) as far as it can tell. */
    static void stoppedOnResult(Minecraft mc, Slot result) {
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

    /**
     * A click that changes nothing (a shift-click on an empty slot of the player's inventory, or of the grid when the
     * inventory has none) sent with a state id the server does not have: the server answers with the whole screen, so
     * this side's view is the server's again. False when no slot is empty.
     */
    static boolean syncClick(Minecraft mc) {
        settle = 0;
        // The player's own slots first: right after a viewer's grid fill this side may not show the fill yet, and a
        // shift-click on a grid cell that only looks empty here would likely take the item the fill put there back out.
        for (boolean grid : new boolean[] {false, true}) {
            for (Slot slot : menu.slots) {
                boolean ok = grid ? gridSlots.contains(slot)
                    : slot.container instanceof Inventory && slot.getContainerSlot() < 36;
                if (slot.hasItem() || !ok) continue;
                forceAnswer();
                sendCount++;
                mc.gameMode.handleContainerInput(menu.containerId, slot.index, 0, ContainerInput.QUICK_MOVE, mc.player);
                awaiting = true;
                waited = 0;
                lastWasCraft = false;
                return true;
            }
        }
        return false;
    }

    /**
     * The WAIT_RESULT phase of {@link Runner#onTick}: reads the filled grid and starts the craft.
     * Returns true where the original case body returned, so the tick ends.
     */
    static boolean waitResultPhase(Minecraft mc, Slot result) {
        if (result.hasItem()) {
            if (wrongResult(result)) return true;
            if (ae2) {
                // AE2 refills the grid from the network after each craft: one craft action per wanted craft
                clicksLeft = walker.remaining();
                clicksMade = 0;
                hadTool = false;
                awaiting = false;
                phase = Phase.CLICKS;
                waited = 0;
                clicks(mc, result);
                return true;
            }
            // Also after a single-set fill (see NEXT: no batched fill into a tool-carrying
            // grid): the top-up batches the crafts itself, with verified clicks and its own server sync
            if (gridSets() == 1 && walker.remaining() > 1 && startTopUp()) return true;
            long sets = full ? gridSets() : 1;
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
                return true;
            }
            if (full && sets > walker.remaining()) {
                // The grid holds more sets than crafts are left. One shift-click per step would let
                // vanilla's doClick repeat loop craft the whole over-large grid and credit all of it, so
                // the tail is trimmed instead: one set goes in, and the rest of this recipe is crafted one
                // craft at a time. Tool recipes still batch
                // through the room-clamped startTopUp. The grid is emptied with verified clicks first (see
                // Phase.GRID_CLEAR) and the re-fill then goes into an empty grid from Phase.TRIM: JEI would
                // empty this full grid optimistically itself, and the same refusal as in NEXT could follow
                // it.
                fillCleared.clear();
                resumeAfterFill = Phase.TRIM;
                awaiting = false;
                waited = 0;
                phase = Phase.GRID_CLEAR;
                return true;
            }
            if (full) {
                // The grid of a batch has been taken from the server's own screen above; craft it in
                // checked steps (see startClicks): one shift-click per step, or cursor picks for a recipe
                // in singleClicks or when the grid holds more sets than the inventory has room for or than
                // is still wanted.
                startClicks(mc, result);
                return true;
            }
            // Autocrafting Speed: waits here, result in view, for a tick with budget left
            if (!Speed.fits(Math.max(1, gridSets()))) return false;
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
        return false;
    }

    /**
     * The WAIT_CLEAR phase of {@link Runner#onTick}: counts what the shift-click crafted.
     * Returns true where the original case body returned, so the tick ends.
     */
    static boolean waitClearPhase(Minecraft mc, Slot result) {
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
                    return true;
                }
                NeiAddon.LOGGER.info("Autocrafting: the batch shift-click on {} crafted nothing on the server, giving up on this recipe", current);
                abandonVisit(current, "the server crafted nothing");
                phase = Phase.NEXT;
                return true;
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
                return true;
            }
            stoppedOnResult(mc, result);
            startCleanup();
        }
        return false;
    }

    /**
     * The SYNC phase of {@link Runner#onTick}: waits for the screen fetched from the server.
     * Returns true where the original case body returned, so the tick ends.
     */
    static boolean syncPhase(Minecraft mc) {
        if (awaiting) {
            if (menu.getStateId() == clickState && ++waited <= TIMEOUT) return true;
            // the id also moves with updates the server sent before the answer: give the answer itself a
            // moment to arrive before JEI looks at the screen again
            if (settle++ < SETTLE_TICKS) return true;
            awaiting = false;
            waited = 0;
            if (!storageTaken) snapshotStorage();
            viewFetched = true;
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
            // A sync that ran counts as the fetch, even with no slot to sync through (see nextPhase)
            viewFetched = true;
            // No slot to sync through: carry on without the sync
            phase = resumeAfterSync;
            resumeAfterSync = Phase.NEXT;
        } else {
            return true;
        }
        return false;
    }
}
