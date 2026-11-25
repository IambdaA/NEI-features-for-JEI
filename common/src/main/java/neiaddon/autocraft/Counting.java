package neiaddon.autocraft;

import java.util.List;
import java.util.Map;
import neiaddon.CraftSlots;
import neiaddon.RecipeData;
import neiaddon.Tools;
import neiaddon.chain.CraftCount;
import neiaddon.chain.GridMath;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import static neiaddon.autocraft.Run.*;

/**
 * Counts how many crafts a click made, from the grid and the server's answers, and describes the grid for the log.
 */
final class Counting {

    /**
     * How many crafts the last shift-click did, from the grid before and after: each craft takes one item from every
     * filled slot. A slot whose item changed (a bucket left behind) or emptied counts as all used. Never below 1: a grid
     * of nothing but tools cannot be measured (a tool is not used up), and one set was asked for.
     */
    static long craftsDone() {
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
     * one (a GregTech tool, see {@link ClickPath#unstackableInput(String)}).
     */
    static long craftsDoneBatch() {
        return CraftCount.batch(craftsMeasured());
    }

    /**
     * The smallest drop over the grid's slots that are used up, or {@link Long#MAX_VALUE} when every filled slot holds a
     * tool (see {@link #craftsDone()}).
     */
    static long craftsMeasured() {
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

    /** How many full sets the crafting grid holds: the smallest stack among its filled slots. */
    static long gridSets() {
        List<Slot> filled = new java.util.ArrayList<>();
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && !Tools.isTool(slot.getItem())) filled.add(slot);
        }
        long[] counts = new long[filled.size()];
        for (int i = 0; i < counts.length; i++) counts[i] = filled.get(i).getItem().getCount();
        return GridMath.gridSets(counts);
    }

    /** A one-line picture of the grid for the log: count, id, and a tool's damage. */
    static String describeGrid() {
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
    static List<Slot> craftingGrid(AbstractContainerMenu m) {
        return CraftSlots.grid(m);
    }

    static void snapshot() {
        before.clear();
        for (Slot slot : gridSlots) before.put(slot, slot.getItem().copy());
        // The whole sets in this grid: vanilla's doClick repeat loop crafts inside one
        // shift-click until the grid runs out, so this is the most a step can craft; creditBurst() stops above it
        snapshotSets = gridSets();
        // The new step starts with nothing credited against this snapshot (see
        // {@link Run#stepCredit} and the empty-result read path of clicks())
        stepCredit = 0;
    }

    /** Like {@link #craftsDone()}, but 0 when the grid did not change at all. */
    static long craftsDoneExact() {
        long done = craftsMeasured();
        return GridMath.craftsExact(done);
    }

    static void noteCrafted(long n) {
        walker.crafted(n);
        craftedBy.merge(current, n, Long::sum);
        // The visit's crafts, for the one-set-a-time note (see startTopUp)
        visitCrafts += n;
    }
}
