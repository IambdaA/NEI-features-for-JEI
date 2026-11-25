/*
 * Crafting-grid maths for the NEI addon for JEI.
 *
 * The pure part of the autocraft click path's decisions: grid sizes, where a recipe sits in the 3x3 grid, which cell a
 * missing tool goes to, and how big a burst of result clicks is. The game side gathers the numbers and flags (counts,
 * max stack sizes, which ingredient accepts which item) and passes them in, so tools/AutoCraftLogicCheck.java tests the
 * rules the click path really uses, without Minecraft classes.
 */
package neiaddon.chain;

import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Pure arithmetic and placement rules of the autocraft click path. Grid indices are those of the 3x3 crafting grid,
 * row-major: 0 is top left, 8 is bottom right.
 */
public final class GridMath {

    private GridMath() {}

    /** Does recipe cell {@code cell}'s ingredient accept the item that sits in grid slot {@code slot}? */
    @FunctionalInterface
    public interface CellAccepts {
        boolean test(int cell, int slot);
    }

    /** How many full sets the grid holds: the smallest of the counts of its filled non-tool slots, 0 with none. */
    public static long gridSets(long[] counts) {
        long sets = Long.MAX_VALUE;
        for (long count : counts) sets = Math.min(sets, count);
        return sets == Long.MAX_VALUE ? 0 : sets;
    }

    /** A measured drop made exact: 0 when nothing could be measured, never below 0. */
    public static long craftsExact(long measured) {
        return measured == Long.MAX_VALUE ? 0 : Math.max(0, measured);
    }

    /**
     * How many result clicks one PICKUP-fallback step sends: at most {@code clickBurst}, and never more than the sets
     * the grid holds, the room for result stacks, the crafts still wanted, or what any tool has left
     * ({@code toolCrafts}, one entry per tool in the grid). At least 1.
     */
    public static int burstSize(long gridSets, long room, long clicksLeft, long[] toolCrafts, int clickBurst) {
        long limit = Math.min(Math.min(gridSets, room), clicksLeft);
        for (long left : toolCrafts) limit = Math.min(limit, left);
        return (int) Math.min(clickBurst, Math.max(1, limit));
    }

    /**
     * How many items one inventory slot can still take of a result: its whole {@code max} when empty, {@code max} minus
     * what it holds when it holds the same item, else 0.
     */
    public static long slotRoom(boolean empty, boolean sameItem, int max, int heldCount) {
        long room = 0;
        if (empty) room += max;
        else if (sameItem) room += max - heldCount;
        return room;
    }

    /**
     * How many result stacks of {@code outCount} items (at least 1) fit, from the room of each inventory slot in order
     * ({@link #slotRoom}). The counting stops at {@code cap}.
     */
    public static int stackRoom(int outCount, int cap, long[] slotRooms) {
        int each = Math.max(1, outCount);
        long room = 0;
        for (long slotRoom : slotRooms) {
            room += slotRoom;
            if (room / each >= cap) return cap;
        }
        return (int) Math.max(0, room / each);
    }

    /**
     * The side of the square a recipe list of {@code size} cells makes (a bridged recipe is a whole row-major square),
     * or -1 when it is not a square.
     */
    public static int squareSide(int size) {
        int square = (int) Math.round(Math.sqrt(size));
        return square * square != size ? -1 : square;
    }

    /** Is a w x h recipe of {@code cells} cells one that fits the 3x3 grid? */
    public static boolean validSize(int w, int h, int cells) {
        return !(w < 1 || h < 1 || w > 3 || h > 3 || w * h != cells);
    }

    /**
     * The grid slot the viewer's fill moves ingredient i of a shaped recipe's own (trimmed) w x h grid to. This is the
     * table of JEI's {@code CraftingGridHelper.getCraftingIndex}: a 1-wide grid sits in the middle column (1x1 dead
     * centre, slot 4), a 1-tall grid in the middle row, a 2-wide grid in the left two columns (2x2: 0,1,3,4), a 2-tall
     * grid in the bottom two rows (3x2: 3,4,5,6,7,8); 3x3 is the identity.
     */
    public static int craftingIndex(int i, int w, int h) {
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

    /** The grid slot of recipe index i when the pattern's top left corner is at column {@code ox}, row {@code oy}. */
    public static int offsetIndex(int i, int w, int ox, int oy) {
        return (oy + i / w) * 3 + ox + i % w;
    }

    /**
     * Does a placement of a shaped recipe's cells fit the 3x3 grid as it is now? {@code place} maps recipe index i to
     * its grid slot, {@code cellEmpty[i]} is whether the recipe cell is empty, {@code takesTool[i]} whether its
     * ingredient accepts the tool, {@code gridHasItem[k]} whether grid slot k holds an item. Every grid slot outside
     * the pattern must be empty. A pattern cell that accepts the tool may be empty or hold an item its ingredient
     * accepts; any other non-empty cell must hold an item its ingredient accepts.
     */
    public static boolean fitsPlacement(boolean[] cellEmpty, boolean[] takesTool, boolean[] gridHasItem,
        CellAccepts accepts, IntUnaryOperator place) {
        int n = cellEmpty.length;
        boolean[] inPattern = new boolean[9];
        int[] at = new int[n];
        for (int i = 0; i < n; i++) {
            at[i] = place.applyAsInt(i);
            if (at[i] < 0 || at[i] >= 9) return false;
            inPattern[at[i]] = true;
        }
        for (int k = 0; k < 9; k++) {
            if (!inPattern[k] && gridHasItem[k]) return false;
        }
        for (int i = 0; i < n; i++) {
            if (cellEmpty[i]) continue;
            boolean heldEmpty = !gridHasItem[at[i]];
            if (takesTool[i]) {
                if (!heldEmpty && !accepts.test(i, at[i])) return false;
            } else if (heldEmpty || !accepts.test(i, at[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * The offset {column, row} of the 3x3 grid where the fill really put a shaped recipe of size w x h, or null to
     * keep the JEI table's placement. The table's placement is used when it fits; otherwise the one other offset that
     * fits is the fill's; with none, or several, null. Mirrored placements are not tried.
     */
    public static int[] fillOffset(int w, int h, boolean[] cellEmpty, boolean[] takesTool, boolean[] gridHasItem,
        CellAccepts accepts) {
        if (fitsPlacement(cellEmpty, takesTool, gridHasItem, accepts, i -> craftingIndex(i, w, h))) return null;
        int[] only = null;
        int fitting = 0;
        for (int ox = 0; ox <= 3 - w; ox++) {
            for (int oy = 0; oy <= 3 - h; oy++) {
                int x = ox;
                int y = oy;
                if (!fitsPlacement(cellEmpty, takesTool, gridHasItem, accepts, i -> offsetIndex(i, w, x, y))) continue;
                only = new int[] {x, y};
                fitting++;
            }
        }
        if (fitting != 1) return null;
        return only;
    }

    /**
     * The grid slot a missing tool goes to: the first recipe cell that is non-empty and accepts the tool, whose slot
     * ({@code used} maps recipe index to grid slot) is inside a grid of {@code gridSize} slots and not in
     * {@code taken}. -1 when there is none.
     */
    public static int toolCell(boolean[] cellEmpty, boolean[] takesTool, IntUnaryOperator used, int gridSize,
        IntPredicate taken) {
        for (int i = 0; i < cellEmpty.length; i++) {
            if (cellEmpty[i] || !takesTool[i]) continue;
            int at = used.applyAsInt(i);
            if (at < 0 || at >= gridSize || taken.test(at)) continue;
            return at;
        }
        return -1;
    }
}
