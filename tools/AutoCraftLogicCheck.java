import java.util.Set;
import java.util.function.IntUnaryOperator;

import neiaddon.chain.GridMath;

/**
 * Checks the pure decisions of the autocraft click path ({@link GridMath}) without Minecraft: batch size, room for
 * result stacks, where a recipe sits in the 3x3 grid, and which cell a missing tool goes to. The expected values were
 * worked out by hand from the code before the split into GridMath (ClickPath.burstSize / stackRoom,
 * ToolCells.toolCell / fillOffset / fitsPlacement / craftingIndex, Counting.gridSets / craftsDoneExact), not taken
 * from GridMath.
 *
 * <pre>
 * javac -d /tmp/alcheck common/src/main/java/neiaddon/chain/*.java tools/AutoCraftLogicCheck.java
 * java -cp /tmp/alcheck AutoCraftLogicCheck
 * </pre>
 */
public class AutoCraftLogicCheck {

    static int failures;

    static void eq(String what, long expected, long actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    static void eq(String what, boolean expected, boolean actual) {
        if (expected != actual) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }

    static void offset(String what, int[] expected, int[] actual) {
        boolean ok = expected == null ? actual == null
            : actual != null && actual.length == 2 && actual[0] == expected[0] && actual[1] == expected[1];
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + java.util.Arrays.toString(expected) + ", got "
                + java.util.Arrays.toString(actual));
        }
    }

    /** A recipe cell: the names of the items its ingredient accepts, none for an empty cell. */
    static Set<String> any(String... names) {
        return Set.of(names);
    }

    /** The flags the game side gathers for a recipe and a grid. */
    record Setup(boolean[] empty, boolean[] takes, boolean[] has, GridMath.CellAccepts accepts) {}

    static Setup setup(Set<String>[] cells, String[] grid, String tool) {
        boolean[] empty = new boolean[cells.length];
        boolean[] takes = new boolean[cells.length];
        for (int i = 0; i < cells.length; i++) {
            empty[i] = cells[i].isEmpty();
            takes[i] = !empty[i] && cells[i].contains(tool);
        }
        boolean[] has = new boolean[9];
        for (int k = 0; k < 9; k++) has[k] = grid[k] != null;
        return new Setup(empty, takes, has, (cell, slot) -> cells[cell].contains(grid[slot]));
    }

    @SafeVarargs
    static Set<String>[] cells(Set<String>... c) {
        return c;
    }

    static String[] grid(String... slots) {
        String[] g = new String[9];
        System.arraycopy(slots, 0, g, 0, slots.length);
        return g;
    }

    static void indexTable() {
        int[][] expect = {
            // w, h, then the slot of each recipe index
            {1, 1, 4},
            {1, 2, 1, 4},
            {1, 3, 1, 4, 7},
            {2, 1, 3, 4},
            {3, 1, 3, 4, 5},
            {2, 2, 0, 1, 3, 4},
            {2, 3, 0, 1, 3, 4, 6, 7},
            {3, 2, 3, 4, 5, 6, 7, 8},
            {3, 3, 0, 1, 2, 3, 4, 5, 6, 7, 8},
        };
        for (int[] row : expect) {
            for (int i = 2; i < row.length; i++) {
                eq("craftingIndex " + row[0] + "x" + row[1] + " i" + (i - 2), row[i],
                    GridMath.craftingIndex(i - 2, row[0], row[1]));
            }
        }
        eq("offsetIndex 1-wide col 2 row 1", 5, GridMath.offsetIndex(0, 1, 2, 1));
        eq("offsetIndex 2-wide i3", 5, GridMath.offsetIndex(3, 2, 1, 0));
        eq("offsetIndex 3x3 identity", 8, GridMath.offsetIndex(8, 3, 0, 0));
    }

    static void sizes() {
        eq("squareSide 9", 3, GridMath.squareSide(9));
        eq("squareSide 4", 2, GridMath.squareSide(4));
        eq("squareSide 1", 1, GridMath.squareSide(1));
        eq("squareSide 6", -1, GridMath.squareSide(6));
        eq("squareSide 0", 0, GridMath.squareSide(0));
        eq("validSize 3x3", true, GridMath.validSize(3, 3, 9));
        eq("validSize 1x3", true, GridMath.validSize(1, 3, 3));
        eq("validSize 0x1", false, GridMath.validSize(0, 1, 0));
        eq("validSize 4x1", false, GridMath.validSize(4, 1, 4));
        eq("validSize 2x2 of 3", false, GridMath.validSize(2, 2, 3));
        eq("validSize 3x0", false, GridMath.validSize(3, 0, 0));
    }

    static void counts() {
        eq("gridSets none", 0, GridMath.gridSets(new long[] {}));
        eq("gridSets min", 1, GridMath.gridSets(new long[] {64, 8, 1}));
        eq("gridSets one", 5, GridMath.gridSets(new long[] {5}));
        eq("craftsExact unmeasurable", 0, GridMath.craftsExact(Long.MAX_VALUE));
        eq("craftsExact negative", 0, GridMath.craftsExact(-3));
        eq("craftsExact zero", 0, GridMath.craftsExact(0));
        eq("craftsExact 7", 7, GridMath.craftsExact(7));
    }

    static void room() {
        eq("slotRoom empty 64", 64, GridMath.slotRoom(true, false, 64, 0));
        eq("slotRoom empty max 16", 16, GridMath.slotRoom(true, false, 16, 0));
        eq("slotRoom empty max 1", 1, GridMath.slotRoom(true, false, 1, 0));
        eq("slotRoom same 10 of 64", 54, GridMath.slotRoom(false, true, 64, 10));
        eq("slotRoom same full", 0, GridMath.slotRoom(false, true, 16, 16));
        eq("slotRoom other item", 0, GridMath.slotRoom(false, false, 64, 3));
        eq("stackRoom cap hit", 4, GridMath.stackRoom(1, 4, new long[] {64, 64}));
        eq("stackRoom zero room", 0, GridMath.stackRoom(1, 4, new long[] {0}));
        eq("stackRoom no slots", 0, GridMath.stackRoom(1, 4, new long[] {}));
        eq("stackRoom result of 2", 1, GridMath.stackRoom(2, 4, new long[] {3}));
        eq("stackRoom result of 0 counts as 1", 3, GridMath.stackRoom(0, 4, new long[] {3}));
        eq("stackRoom 3 then 1 reaches cap exactly", 4, GridMath.stackRoom(1, 4, new long[] {3, 1}));
        eq("stackRoom 3 stays under cap", 3, GridMath.stackRoom(1, 4, new long[] {3, 0}));
        eq("stackRoom stops at the cap, not the sum", 2, GridMath.stackRoom(1, 2, new long[] {64}));
    }

    static void burst() {
        int burst = 4;
        // #46 rotor: plates x4, screw, ring are 64 each, so 64 sets; plenty of room and crafts wanted, hammer 100 left
        eq("burst 64 plates, hammer 100", 4, GridMath.burstSize(64, 64, 64, new long[] {100}, burst));
        // hammer with 2 crafts left caps it
        eq("burst 64 plates, hammer 2", 2, GridMath.burstSize(64, 64, 64, new long[] {2}, burst));
        // hammer spent: 0 left still sends one click
        eq("burst hammer 0 left", 1, GridMath.burstSize(64, 64, 64, new long[] {0}, burst));
        // 8-craft batch: 8 sets
        eq("burst 8 plates, hammer 100", 4, GridMath.burstSize(8, 64, 8, new long[] {100}, burst));
        eq("burst 8 plates, hammer 3", 3, GridMath.burstSize(8, 64, 8, new long[] {100, 3, 50}, burst));
        // three tools in the grid (hammer, file, screwdriver): the lowest wins
        eq("burst three tools", 4, GridMath.burstSize(64, 64, 64, new long[] {9, 5, 7}, burst));
        eq("burst room 3", 3, GridMath.burstSize(64, 3, 64, new long[] {}, burst));
        eq("burst zero room", 1, GridMath.burstSize(64, 0, 64, new long[] {}, burst));
        eq("burst one set", 1, GridMath.burstSize(1, 64, 64, new long[] {}, burst));
        eq("burst one click left", 1, GridMath.burstSize(64, 64, 1, new long[] {}, burst));
        eq("burst no sets", 1, GridMath.burstSize(0, 64, 64, new long[] {}, burst));
        eq("burst limit equals burst", 4, GridMath.burstSize(4, 4, 4, new long[] {4}, burst));
        eq("burst burst of 16", 16, GridMath.burstSize(64, 64, 64, new long[] {}, 16));
        eq("burst burst of 16, 5 sets", 5, GridMath.burstSize(5, 64, 64, new long[] {}, 16));
    }

    static void placement() {
        Set<String> plate = any("plate");
        Set<String> hammer = any("hammer");
        Set<String> screw = any("screw");
        Set<String> ring = any("ring");
        Set<String> file = any("file");
        Set<String> driver = any("driver");
        Set<String> none = any();
        Set<String> a = any("a", "tool");
        Set<String> t = any("tool");
        Set<String> b = any("b");

        // ---- #46 rotor: plate hammer plate / screw ring file / plate screwdriver plate
        Set<String>[] rotor = cells(plate, hammer, plate, screw, ring, file, plate, driver, plate);
        String[] noTools = grid("plate", null, "plate", "screw", "ring", null, "plate", null, "plate");
        Setup s = setup(rotor, noTools, "hammer");
        IntUnaryOperator id = i -> GridMath.craftingIndex(i, 3, 3);
        eq("rotor hammer cell", 1, GridMath.toolCell(s.empty, s.takes, id, 9, k -> false));
        eq("rotor hammer cell taken", -1, GridMath.toolCell(s.empty, s.takes, id, 9, k -> k == 1));
        eq("rotor screwdriver cell", 7,
            GridMath.toolCell(setup(rotor, noTools, "driver").empty, setup(rotor, noTools, "driver").takes, id, 9,
                k -> false));
        eq("rotor file cell", 5,
            GridMath.toolCell(setup(rotor, noTools, "file").empty, setup(rotor, noTools, "file").takes, id, 9,
                k -> false));
        eq("rotor ring is not a tool cell for the hammer", -1,
            GridMath.toolCell(setup(rotor, noTools, "wrench").empty, setup(rotor, noTools, "wrench").takes, id, 9,
                k -> false));
        // the file and the screwdriver are missing too, so the table's placement does not fit: no offset exists
        offset("rotor 3x3 missing file", null, GridMath.fillOffset(3, 3, s.empty, s.takes, s.has, s.accepts));
        // with file and screwdriver in, the table fits
        String[] withTools = grid("plate", null, "plate", "screw", "ring", "file", "plate", "driver", "plate");
        Setup full = setup(rotor, withTools, "hammer");
        eq("rotor fits identity", true,
            GridMath.fitsPlacement(full.empty, full.takes, full.has, full.accepts, id));
        eq("rotor does not fit with a foreign item", false,
            GridMath.fitsPlacement(s.empty, s.takes, s.has, s.accepts, id));
        offset("rotor 3x3 fits", null, GridMath.fillOffset(3, 3, full.empty, full.takes, full.has, full.accepts));
        // a full grid (hammer in too) still fits: the tool cell may hold an item its ingredient accepts
        String[] all = grid("plate", "hammer", "plate", "screw", "ring", "file", "plate", "driver", "plate");
        Setup everything = setup(rotor, all, "hammer");
        eq("rotor full grid fits", true,
            GridMath.fitsPlacement(everything.empty, everything.takes, everything.has, everything.accepts, id));
        // a full grid whose middle item is wrong does not
        String[] wrong = all.clone();
        wrong[4] = "plate";
        Setup bad = setup(rotor, wrong, "hammer");
        eq("rotor full grid wrong ring", false,
            GridMath.fitsPlacement(bad.empty, bad.takes, bad.has, bad.accepts, id));

        // ---- 1-wide recipe [b, tool, b] in the 3x3 grid, under both placement rules
        Set<String>[] column = cells(b, t, b);
        IntUnaryOperator table = i -> GridMath.craftingIndex(i, 1, 3);
        // JEI: middle column, slots 1, 4, 7
        String[] jei = grid(null, "b", null, null, null, null, null, "b");
        Setup j = setup(column, jei, "tool");
        offset("1-wide JEI keeps the table", null, GridMath.fillOffset(1, 3, j.empty, j.takes, j.has, j.accepts));
        eq("1-wide JEI tool slot", 4, GridMath.toolCell(j.empty, j.takes, table, 9, k -> false));
        // EMI / TMRV: column 0, slots 0, 3, 6
        String[] emi = grid("b", null, null, null, null, null, "b");
        Setup e = setup(column, emi, "tool");
        int[] found = GridMath.fillOffset(1, 3, e.empty, e.takes, e.has, e.accepts);
        offset("1-wide EMI column 0", new int[] {0, 0}, found);
        IntUnaryOperator shifted = i -> GridMath.offsetIndex(i, 1, found[0], found[1]);
        eq("1-wide EMI tool slot", 3, GridMath.toolCell(e.empty, e.takes, shifted, 9, k -> false));
        // column 2
        String[] right = grid(null, null, "b", null, null, null, null, null, "b");
        Setup r = setup(column, right, "tool");
        offset("1-wide column 2", new int[] {2, 0}, GridMath.fillOffset(1, 3, r.empty, r.takes, r.has, r.accepts));
        // the tool already in the EMI column's middle still fits it
        String[] emiTool = grid("b", null, null, "tool", null, null, "b");
        Setup et = setup(column, emiTool, "tool");
        offset("1-wide EMI with tool in", new int[] {0, 0},
            GridMath.fillOffset(1, 3, et.empty, et.takes, et.has, et.accepts));

        // nothing fits: an item at slot 4 and one at slot 0 cannot both be in the pattern
        String[] mixed = grid("b", null, null, null, "b");
        Setup m = setup(column, mixed, "tool");
        offset("1-wide nothing fits", null, GridMath.fillOffset(1, 3, m.empty, m.takes, m.has, m.accepts));

        // several offsets fit: a 1x2 recipe of two tool cells and one item at slot 3
        Set<String>[] two = cells(a, a);
        String[] one = grid(null, null, null, "tool");
        Setup o = setup(two, one, "tool");
        offset("ambiguous offsets", null, GridMath.fillOffset(1, 2, o.empty, o.takes, o.has, o.accepts));

        // 1x1 recipe whose item sits in slot 0: only offset (0, 0) fits
        Set<String>[] single = cells(b);
        String[] corner = grid("b");
        Setup c = setup(single, corner, "tool");
        offset("1x1 in the corner", new int[] {0, 0}, GridMath.fillOffset(1, 1, c.empty, c.takes, c.has, c.accepts));
        // 1x1 recipe on an empty grid with a non-tool cell: nothing fits
        Setup ce = setup(single, grid(), "tool");
        offset("1x1 empty grid", null, GridMath.fillOffset(1, 1, ce.empty, ce.takes, ce.has, ce.accepts));
        // place out of range or negative
        eq("place beyond 9", false, GridMath.fitsPlacement(ce.empty, ce.takes, ce.has, ce.accepts, i -> 9));
        eq("place negative", false, GridMath.fitsPlacement(ce.empty, ce.takes, ce.has, ce.accepts, i -> -1));

        // ---- 2x2 recipe [b, tool, b, b]: 2x2 sits in the left two columns, slots 0, 1, 3, 4
        Set<String>[] square = cells(b, t, b, b);
        IntUnaryOperator two2 = i -> GridMath.craftingIndex(i, 2, 2);
        eq("2x2 tool slot", 1, GridMath.toolCell(setup(square, grid(), "tool").empty,
            setup(square, grid(), "tool").takes, two2, 9, k -> false));
        eq("2x2 tool slot taken", -1, GridMath.toolCell(setup(square, grid(), "tool").empty,
            setup(square, grid(), "tool").takes, two2, 9, k -> k == 1));
        // a 2x2 crafting grid of 4 slots: the same recipe, slot 1 is inside
        eq("2x2 grid of 4", 1, GridMath.toolCell(setup(square, grid(), "tool").empty,
            setup(square, grid(), "tool").takes, two2, 4, k -> false));
        // a cell mapped beyond the grid is skipped
        eq("outside a 4-slot grid", -1, GridMath.toolCell(setup(column, grid(), "tool").empty,
            setup(column, grid(), "tool").takes, table, 4, k -> false));
        // two accepting cells: the first is taken, the second is used (1x2 table: slots 1, 4)
        IntUnaryOperator two1 = i -> GridMath.craftingIndex(i, 1, 2);
        eq("second accepting cell", 4, GridMath.toolCell(o.empty, o.takes, two1, 9, k -> k == 1));
        eq("both taken", -1, GridMath.toolCell(o.empty, o.takes, two1, 9, k -> true));
        // an empty cell is never the tool's cell, even if its set would accept it
        Set<String>[] gap = cells(none, t);
        Setup g = setup(gap, grid(), "tool");
        eq("empty cell skipped", 4, GridMath.toolCell(g.empty, g.takes, i -> GridMath.craftingIndex(i, 1, 2), 9,
            k -> false));
        eq("squareSide for a bridged 3x3 recipe", 3, GridMath.squareSide(rotor.length));
    }

    public static void main(String[] args) {
        indexTable();
        sizes();
        counts();
        room();
        burst();
        placement();
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
