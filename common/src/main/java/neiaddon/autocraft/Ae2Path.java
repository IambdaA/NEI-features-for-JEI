package neiaddon.autocraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import neiaddon.Ae2Crafting;
import neiaddon.FluidStacks;
import neiaddon.NeiAddon;
import neiaddon.RecipeData;
import neiaddon.RecipeLookup;
import neiaddon.Tools;
import neiaddon.chain.ChainItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static neiaddon.autocraft.Cleanup.*;
import static neiaddon.autocraft.ClickPath.*;
import static neiaddon.autocraft.Counting.*;
import static neiaddon.autocraft.Run.*;
import static neiaddon.autocraft.Runner.*;
import static neiaddon.autocraft.Visits.*;

/**
 * The AE2 path: stores the recipe's ingredients in the network, lets AE2 refill the grid and puts results back
 * into the network.
 */
final class Ae2Path {

    /**
     * AE2: before the fill, the recipe's ingredients that the player's inventory holds go into the AE2 network. AE2
     * refills the grid only from the network, so a grid filled from the inventory is empty again after every craft,
     * and each craft then costs a visit of its own. Returns true when something is to be stored: the phase is then
     * {@link Phase#AE2_STORE}, and the visit's fill follows once {@link #storeFinish} has run.
     */
    static boolean storeFromInventory(String recipeId) {
        storeSlots.clear();
        storeAt = 0;
        storeStored = 0;
        storeWait = 0;
        for (Slot slot : menu.slots) {
            if (!slot.hasItem() || !(slot.container instanceof Inventory) || slot.getContainerSlot() >= 36) continue;
            if (storable(slot.getItem(), recipeId)) storeSlots.add(slot);
        }
        if (storeSlots.isEmpty()) return false;
        storeRecipe = recipeId;
        awaiting = false;
        waited = 0;
        phase = Phase.AE2_STORE;
        return true;
    }

    /**
     * AE2: whether a stack is one of the recipe's ingredients that the network may take in this run. Tools, fluids
     * and empty-factor ingredients are left to the transfer. So are items that were asked for (they stay with the
     * player) and items the network already refused this run.
     */
    static boolean storable(ItemStack stack, String recipeId) {
        if (stack.isEmpty() || Tools.isTool(stack)) return false;
        String id = RecipeData.idOf(stack);
        String plain = plainId(id);
        if (ae2Refused.contains(plain) || ae2StoreRefused.contains(plain)) return false;
        if (askedFor.stream().anyMatch(asked -> plainId(asked).equals(plain))) return false;
        for (ChainItem ingredient : math.recipeIngredients) {
            if (recipeId.equals(ingredient.recipeId) && !ingredient.emptyFactor() && !leftToTransfer(ingredient)
                && candidates(ingredient).contains(id)) return true;
        }
        return false;
    }

    /**
     * AE2 store, one step per tick: waits for the server's answer to the last shift-click, then checks the slot it
     * went to. A stack that kept its count was not taken by the network, and its item is not tried again this run.
     * Then the next storable stack is shift-clicked. A click that is not answered in time ends the stores of this
     * visit.
     */
    static void storeStep(Minecraft mc) {
        if (awaiting) {
            int id = menu.getStateId();
            // Another update from the server can change the state id before the answer arrives, so the slot is read
            // only once the id has stayed the same for SETTLE_TICKS
            boolean changed = id != storeSeenId;
            if (changed) {
                storeSeenId = id;
                storeQuiet = 0;
            }
            if (id == clickState || changed || storeQuiet++ < SETTLE_TICKS) {
                if (++waited > TIMEOUT) {
                    NeiAddon.LOGGER.info("Autocrafting: the AE2 network did not answer a store click for {}, going on",
                        storeRecipe);
                    storeFinish(mc);
                }
                return;
            }
            awaiting = false;
            ItemStack now = storeSlot.getItem();
            boolean kept = !now.isEmpty() && now.getCount() == storeBefore.getCount()
                && ItemStack.isSameItemSameTags(now, storeBefore);
            if (kept) {
                String plain = plainId(RecipeData.idOf(storeBefore));
                if (ae2StoreRefused.add(plain)) {
                    NeiAddon.LOGGER.info("Autocrafting: the AE2 network did not take {}, it stays in the inventory",
                        storeBefore.getHoverName().getString());
                }
            } else {
                storeStored++;
            }
        }
        while (storeAt < storeSlots.size()) {
            Slot slot = storeSlots.get(storeAt++);
            if (!slot.hasItem() || !storable(slot.getItem(), storeRecipe)) continue;
            storeSlot = slot;
            storeBefore = slot.getItem().copy();
            send(mc, slot.index, true);
            storeSeenId = menu.getStateId();
            storeQuiet = 0;
            return;
        }
        if (storeStored > 0 && storeWait++ < AE2_STORE_SETTLE_TICKS) return;
        storeFinish(mc);
    }

    /**
     * AE2 store: the stores of this visit are over (every slot was tried, or the network did not answer in time).
     * Logs how many stacks it took, then the visit's transfer goes on.
     */
    static void storeFinish(Minecraft mc) {
        String recipeId = storeRecipe;
        if (storeStored > 0) {
            NeiAddon.LOGGER.info("Autocrafting: stored {} stack(s) of {}'s ingredients in the AE2 network "
                + "so AE2 can refill the grid", storeStored, recipeId);
        }
        storeRecipe = null;
        storeSlots.clear();
        storeAt = 0;
        storeSlot = null;
        storeBefore = ItemStack.EMPTY;
        storeStored = 0;
        storeWait = 0;
        awaiting = false;
        // A refused transfer leaves the phase as it is: it has to be NEXT, so its retry or give-up runs as from NEXT
        phase = Phase.NEXT;
        transferVisit(mc, recipeId);
    }

    /**
     * AE2: the grid makes another item than the recipe's, so the recipe is skipped for the rest of the run instead of stopping
     * the chain. Nothing is crafted: the grid goes back into the network (then {@link Phase#AE2_CLEAR} waits for it to be
     * empty), and the end of the run names what did not reach the grid. Without the clear it stops as on other grids.
     */
    static void ae2Skip(String made, String wanted) {
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
     * gets at most {@link Run#AE2_CLEAR_DEPOSITS} deposits while it stays the same stack; after that the clear stops as its
     * timeout does.
     */
    static void ae2Clear(Minecraft mc) {
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
    static String ae2NotInGrid(String recipeId) {
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
    static boolean gridHoldsItemOf(ChainItem ingredient) {
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
     * tool), and a fluid is not an item. Each ingredient's candidates ({@link Visits#candidates}) share the inventory; the ones
     * with fewest candidates get their items first (a slot that takes only X gets X before one that takes X or Y), and each
     * takes its amount from the items it accepts, so one item is not counted for two ingredients.
     */
    static String ae2Missing(String recipeId) {
        return ae2Missing(recipeId, inventory());
    }

    static String ae2Missing(String recipeId, Map<String, Long> inventory) {
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
    static String refusedWhy(RecipeLookup.Target<?> target, Minecraft mc) {
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
    static String ae2MissingTool(String recipeId) {
        return ae2MissingTool(recipeId, inventory());
    }

    /** The same with the inventory already read ({@link Runner#inventory()}, which also sets {@link Run#networkRead}). */
    static String ae2MissingTool(String recipeId, Map<String, Long> inventory) {
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
     * {@link ClickPath#clicks} at an AE2 terminal: each click is AE2's craft action (one craft, or a stack's worth),
     * answered when the cursor changes, and must have added whole crafts' results to it. When the result slot
     * stays empty for {@link Run#AE2_REFILL_TICKS} after a craft, the network is out of an ingredient: the rest of
     * this recipe is left to the chain (its next fill is refused, and the recipe is given up as at any table).
     */
    static void ae2Clicks(Minecraft mc, Slot result) {
        if (ae2PutWait()) return;
        ItemStack carried = menu.getCarried();
        if (awaiting) {
            if (lastWasCraft) {
                if (ItemStack.matches(carried, ae2Carried)) {
                    if (++waited > TIMEOUT) fail("the terminal did not answer a craft");
                    return;
                }
                awaiting = false;
                // A stack craft stops early when the result changes (a tool broke, an ingredient ran out), so any whole
                // number of crafts up to the stack's worth counts
                int grew = carried.getCount() - ae2Carried.getCount();
                boolean added = !carried.isEmpty() && (ae2Carried.isEmpty() || ItemStack.isSameItemSameTags(carried, ae2Carried))
                    && grew > 0 && grew % ae2Adds == 0 && grew / ae2Adds <= ae2Crafts;
                if (!added) {
                    fail("a craft in the terminal did not put its results on the cursor");
                    return;
                }
                noteCrafted(grew / ae2Adds);
                clicksLeft -= grew / ae2Adds;
                if (ae2Crafts > 1) {
                    NeiAddon.LOGGER.info("Autocrafting count: a stack craft of {} made {} of {} crafts, {} left",
                        current, grew / ae2Adds, ae2Crafts, clicksLeft);
                }
            } else {
                if (menu.getStateId() == clickState) {
                    if (++waited > TIMEOUT) fail("the server did not answer a click");
                    return;
                }
                awaiting = false;
            }
        }
        if (clicksLeft > 0 && !result.hasItem()) {
            if (ae2RefillWait++ < AE2_REFILL_TICKS) return;
            NeiAddon.LOGGER.info("Autocrafting: the terminal's grid is not full again after a craft of {}; the network may be out of an ingredient", current);
            clicksLeft = 0;
        }
        ae2RefillWait = 0;
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
        // Autocrafting Speed: nothing is in flight here, so a tick without budget left waits for the next one
        if (!Speed.fits(1)) return;
        ae2Carried = carried.copy();
        ae2Adds = out.getCount();
        // A whole stack per click (crafted on the server) while a stack's worth of crafts is left and the cursor is
        // empty; single crafts for the rest, so the plan's count is never passed
        int perStack = Math.max(1, out.getMaxStackSize() / out.getCount());
        // a stack craft only when its crafts fit what this tick may still ask for
        ae2Crafts = carried.isEmpty() && clicksLeft >= perStack && Speed.allowed(perStack) >= perStack
            && Ae2Crafting.canCraftStack() ? perStack : 1;
        Speed.spend(ae2Crafts);
        if (!(ae2Crafts > 1 ? Ae2Crafting.craftStack(result) : Ae2Crafting.craftOne(result))) {
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
    static void deposit(Minecraft mc, ItemStack carried) {
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
    static boolean ae2ToNetwork(ItemStack carried) {
        if (!ae2) return false;
        String id = plainId(RecipeData.idOf(carried));
        // by item, any subtype: when in doubt it is an item asked for and stays with the player
        return !ae2Refused.contains(id) && askedFor.stream().noneMatch(asked -> plainId(asked).equals(id));
    }

    /**
     * AE2: a stack goes into the inventory. When the network refused it {@link Run#AE2_PUT_TRIES} times, its item skips the
     * network for the rest of the run (no waiting on every later stack of it); the next stack starts the count again.
     */
    static void ae2Landed(ItemStack carried) {
        if (ae2PutTries >= AE2_PUT_TRIES) ae2Refused.add(plainId(RecipeData.idOf(carried)));
        ae2PutTries = 0;
    }

    /**
     * AE2: sends the cursor's stack into the network (the terminal's own put, see {@link Ae2Crafting#putCarriedIntoNetwork});
     * {@link #ae2PutWait} then waits for the cursor to change. False when it cannot be sent, or the network refused this
     * stack {@link Run#AE2_PUT_TRIES} times in a row (see {@link #ae2Landed}).
     */
    static boolean ae2PutIntoNetwork(ItemStack carried) {
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
    static boolean ae2PutWait() {
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
    static Slot inventorySlotFor(ItemStack carried) {
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

    /** AE2: the grid could not be put back into the network; says so, the items stay in the terminal's grid. */
    static void ae2GridLeft() {
        NeiAddon.LOGGER.info("Autocrafting: the AE2 terminal's grid was not cleared");
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
            "§6[NEI Features] §eAutocrafting left items in the terminal's crafting grid."));
    }
}
