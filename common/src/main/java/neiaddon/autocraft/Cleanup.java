package neiaddon.autocraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import neiaddon.AddonConfig;
import neiaddon.Ae2Crafting;
import neiaddon.CraftSlots;
import neiaddon.NeiAddon;
import neiaddon.RecipeData;
import neiaddon.Tools;
import neiaddon.chain.ChainItem;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static neiaddon.autocraft.Ae2Path.*;
import static neiaddon.autocraft.ClickPath.*;
import static neiaddon.autocraft.Counting.*;
import static neiaddon.autocraft.Run.*;
import static neiaddon.autocraft.Runner.*;
import static neiaddon.autocraft.Visits.*;

/**
 * The end of a run and of a batch: empties the grid, gives back what was taken from storage, restores the
 * inventory layout and tops the grid up for a batch.
 */
final class Cleanup {

    // ---------------------------------------------------------------- cleanup
    /** Ends autocrafting by putting the cursor and the grid back into the inventory, one checked click at a time. */
    static void startCleanup() {
        if (walker == null) return;
        cleaned.clear();
        awaiting = false;
        phase = Phase.CLEANUP;
        waited = 0;
    }

    static void cleanup(Minecraft mc) {
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
    static void afterCleanup() {
        if (!layout.isEmpty()) {
            phase = Phase.RESTORE;
            restoreNext = 0;
            restoreClicks = 0;
            pending.clear();
            return;
        }
        stop();
    }

    /**
     * A slot of the container's own storage: not the player's, the grid or the result. Only when the grid is known, so
     * that a grid slot is never taken for storage.
     */
    static boolean storageSlot(Slot slot) {
        return !ae2 && !gridSlots.isEmpty() && !(slot.container instanceof Inventory) && !gridSlots.contains(slot) && !CraftSlots.isResult(slot);
    }

    /** Per item: what the storage slots (or the player's main inventory and hotbar) hold now. */
    static Map<String, Long> counts(boolean storage) {
        Map<String, Long> counts = new HashMap<>();
        for (Slot slot : menu.slots) {
            if (slot.hasItem() && (storage ? storageSlot(slot) : mainInventory(slot))) {
                counts.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem().getCount(), Long::sum);
            }
        }
        return counts;
    }

    static void clearStorage() {
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
    static void snapshotStorage() {
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
    static long owed(String id) {
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
    static long reclaim(String id) {
        ItemStack carried = menu.getCarried();
        long storageNow = counts(true).getOrDefault(id, 0L) + (!carried.isEmpty() && id.equals(RecipeData.idOf(carried)) ? carried.getCount() : 0);
        long gained = storageNow - storageStart.getOrDefault(id, 0L);
        if (gained <= 0) return 0;
        if (askedFor.contains(id)) return gained;
        long lost = playerStart.getOrDefault(id, 0L) - counts(false).getOrDefault(id, 0L);
        return Math.max(0, Math.min(lost, gained));
    }

    /** What the give-back still moves of an item in its current pass. */
    static long toMove(String id) {
        return reclaiming ? reclaim(id) : owed(id);
    }

    /** Where the give-back puts a picked-up stack in its current pass: a storage slot, or (second pass) the player's. */
    static Slot moveTarget(ItemStack stack) {
        return reclaiming ? playerSlotFor(stack) : storageSlotFor(stack);
    }

    /**
     * A main inventory slot for the stack, or null: a slot it was taken from (it held that item at the start and holds
     * fewer now), else a stack of the same item with room, else an empty slot.
     */
    static Slot playerSlotFor(ItemStack stack) {
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
    static Slot storageBackSlot(ItemStack stack) {
        for (Slot slot : menu.slots) {
            if (storageSlot(slot) && inMenu(slot) && (pickedFrom == null || sameKind(slot, List.of(pickedFrom))) && fits(slot, stack)) return slot;
        }
        return inventorySlotFor(stack);
    }

    /**
     * When the give-back starts: the items this run crafted on the way (results of its recipes that were crafted, not the
     * items asked for), and the storage slots the crafting took items from (they hold less of their item than at the start).
     */
    static void noteMadeOnTheWay() {
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
    static long gainedIn(Slot slot) {
        ItemStack was = slotStart.getOrDefault(slot, ItemStack.EMPTY), now = slot.getItem();
        if (was.isEmpty() || !RecipeData.idOf(was).equals(RecipeData.idOf(now))) return now.getCount();
        return Math.max(0, now.getCount() - was.getCount());
    }

    /**
     * After the cleanup: moves leftovers the container's storage gave up from the player's inventory back into it, one
     * checked click at a time. A stack is picked up, as many as owed are put into the storage (the whole stack, or one
     * at a time), and the rest goes back where it was. An item whose click into the storage moved nothing is left alone.
     */
    static void giveBack(Minecraft mc) {
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
    static boolean better(Slot slot, Slot other) {
        boolean gained = gainedIn(slot) > 0, otherGained = gainedIn(other) > 0;
        if (gained != otherGained) return gained;
        return slot.getItem().getCount() < other.getItem().getCount();
    }

    /** Can the slot take the whole stack (empty, or the same item with room)? */
    static boolean fits(Slot slot, ItemStack stack) {
        if (!slot.mayPlace(stack)) return false;
        ItemStack in = slot.getItem();
        return in.isEmpty() || ItemStack.isSameItemSameComponents(in, stack) && in.getCount() + stack.getCount() <= Math.min(in.getMaxStackSize(), slot.getMaxStackSize(in));
    }

    /**
     * A storage slot for the stack, or null: a slot its item was taken from (it held that item at the start and holds
     * fewer now), else a stack of the same item with room, else an empty slot; the last two only of the same kind as a
     * slot the item was taken from (the same class on the same container or item handler). So a slot of another kind (a filter slot,
     * another mod's ghost slot) is not used, and the item still goes back when something else filled the slot it came
     * from (crafted items that the storage itself took).
     */
    static Slot storageSlotFor(ItemStack stack) {
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
    static boolean sameKind(Slot slot, List<Slot> others) {
        for (Slot other : others) {
            if (other.getClass() == slot.getClass() && other.container == slot.container && CraftSlots.sameHandler(slot, other)) return true;
        }
        return false;
    }

    /** Is the slot still this index's slot of the menu (a click goes by its index)? */
    static boolean inMenu(Slot slot) {
        return slot.index >= 0 && slot.index < menu.slots.size() && menu.slots.get(slot.index) == slot;
    }

    /** Does the slot hold the stack's item (same tags) with room for at least one more? */
    static boolean hasRoom(Slot slot, ItemStack stack) {
        ItemStack in = slot.getItem();
        return ItemStack.isSameItemSameComponents(in, stack) && in.getCount() < Math.min(in.getMaxStackSize(), slot.getMaxStackSize(in));
    }

    static boolean mainInventory(Slot slot) {
        return slot.container instanceof Inventory && slot.getContainerSlot() < 36;
    }

    static void snapshotLayout() {
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
    static void restore(Minecraft mc) {
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

    static boolean mainSlot(Slot slot) {
        return slot.container instanceof Inventory && slot.getContainerSlot() < 36 && !gridSlots.contains(slot);
    }

    /**
     * A slot the top-up may take items from: the player's main inventory and hotbar, or the container's own storage (a
     * crafting station's side chest, a Sophisticated storage), so ingredients kept there fill the grid too.
     */
    static boolean topSourceSlot(Slot slot) {
        return mainSlot(slot) || storageSlot(slot) && slot.hasItem() && slot.mayPickup(Minecraft.getInstance().player);
    }

    /**
     * JEI only moves one set into the grid when the recipe has a tool (an item of one per stack), so a recipe with a
     * hammer or a file would craft one at a time. Here the other slots get more of their items from the inventory and
     * the container's own storage (whole or half stacks where they fit, single items for the rest), so the grid holds
     * exactly as many sets as are wanted (or as the tool and the items allow) and one shift-click crafts them all. False
     * when there is no tool in the grid or not enough for two sets.
     */
    static boolean startTopUp() {
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
                if (topSourceSlot(slot) && ItemStack.isSameItemSameComponents(slot.getItem(), in)) got += slot.getItem().getCount();
            }
            for (Map.Entry<Slot, Integer> o : perSet.entrySet()) {
                if (ItemStack.isSameItemSameComponents(o.getKey().getItem(), in)) wanted += o.getValue();
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
        // Autocrafting Speed: the one shift-click of the filled grid asks for all its sets at once
        int speed = Speed.limit();
        if (speed > 0 && speed < sets) {
            sets = speed;
            topUpWhy = "the Autocrafting Speed setting allows " + speed + " crafts a tick";
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

    /** How many crafts a tool holds left, for the log: says so when it does not wear. */
    static String holds(long left) {
        return left == Long.MAX_VALUE ? "no wear limit" : Long.toString(left);
    }

    static void topUp(Minecraft mc, Slot result) {
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) fail("the server did not answer while filling the grid");
                return;
            }
            awaiting = false;
        }
        if (topSynced) {
            // The id also moves with the server's slot and cursor updates for the fill clicks, which can arrive before
            // the answer to the empty click: a cursor update from halfway through the fill still shows the stack taken
            // from the inventory. Wait for the id to go quiet, the way SYNC and the click loop do, before reading the
            // screen. A busy player inventory can move the id every tick, so stop watching after TIMEOUT moves.
            if (menu.getStateId() != topSettleId) {
                topSettleId = menu.getStateId();
                topQuiet = 0;
                if (++topMoves <= TIMEOUT) return;
            } else if (topQuiet++ < SETTLE_TICKS) {
                return;
            }
            // A lagging server (a "Can't keep up" of two seconds) can send the empty click's answer after the id has
            // gone quiet, so a cursor that still holds something or a grid short of a set may be an older update. Give
            // the answer up to LATE_TICKS before calling the fill failed.
            if ((!menu.getCarried()
                .isEmpty() || !result.hasItem() || gridSets() < 1) && topLate++ < LATE_TICKS) return;
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
                if (!carried.isEmpty() && ItemStack.isSameItemSameComponents(carried, in)) {
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
                        if (mainSlot(s) && ItemStack.isSameItemSameComponents(s.getItem(), in)) {
                            source = s;
                            break;
                        }
                    }
                    for (Slot s : menu.slots) {
                        if (source != null) break;
                        if (topSourceSlot(s) && ItemStack.isSameItemSameComponents(s.getItem(), in)) source = s;
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
                .getCount() == before && ItemStack.isSameItemSameComponents(menu.getCarried(), carried)) {
                fail("a click while filling the grid did nothing");
                return;
            }
        }
        // one empty click outside the slots with a state id the server does not have: it answers with the whole screen
        topSynced = true;
        forceAnswer();
        topSettleId = clickState;
        topQuiet = 0;
        topMoves = 0;
        topLate = 0;
        clickNoWait(mc, -999, 0);
        awaiting = true;
        waited = 0;
        lastWasCraft = false;
    }

    /**
     * The rest of a picked-up stack goes back where it came from (it is empty, or holds the same item with room for all
     * of it: a storage slot can hold more than one stack), else into any inventory slot that takes it.
     */
    static Slot putBackSlot(ItemStack carried) {
        if (topSource != null && topSource.mayPlace(carried)) {
            ItemStack in = topSource.getItem();
            if (in.isEmpty() || ItemStack.isSameItemSameComponents(in, carried) && in.getCount() + carried.getCount() <= topSource.getMaxStackSize(in)) return topSource;
        }
        return inventorySlotFor(carried);
    }

    /** Is there an inventory slot (main inventory or hotbar) that takes all of the stack? Shift-clicking a result goes there. */
    static boolean roomFor(ItemStack stack) {
        int left = stack.getCount();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || slot.getContainerSlot() >= 36 || !slot.mayPlace(stack)) continue;
            ItemStack in = slot.getItem();
            int max = Math.min(stack.getMaxStackSize(), slot.getMaxStackSize(stack));
            if (in.isEmpty()) left -= max;
            else if (ItemStack.isSameItemSameComponents(in, stack)) left -= max - in.getCount();
            if (left <= 0) return true;
        }
        return false;
    }

    /**
     * The GRID_CLEAR phase of {@link Runner#onTick}: empties the grid with verified clicks.
     * Returns true where the original case body returned, so the tick ends.
     */
    static boolean gridClearPhase(Minecraft mc) {
        // the same wait for the answer as in the cleanup's clicks (send answers through forceAnswer)
        if (awaiting) {
            if (menu.getStateId() == clickState) {
                if (++waited > TIMEOUT) {
                    // a slot the server will not empty (an inventory without room): go on regardless,
                    // the paths below still handle a grid that stays filled
                    NeiAddon.LOGGER.info("Autocrafting: the server did not answer a click emptying the grid");
                    awaiting = false;
                } else return true;
            } else awaiting = false;
        }
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && fillCleared.add(slot)) {
                send(mc, slot.index, true);
                return true;
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
        return false;
    }
}
