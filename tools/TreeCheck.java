import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainItem.Type;
import neiaddon.chain.ReadableNumber;
import neiaddon.chain.TreeLayout;
import neiaddon.chain.TreeMath;
import neiaddon.chain.TreeSlot;
import neiaddon.chain.TreeStats;
import neiaddon.chain.TreeStatsLayout;

/**
 * The crafting tree maths and layout (NEI's CraftingTreeMath / CraftingTreeCanvas) without Minecraft.
 *
 * <pre>
 * javac -d /tmp/tcheck src/main/java/neiaddon/chain/*.java tools/TreeCheck.java
 * java -cp /tmp/tcheck TreeCheck
 * </pre>
 */
public class TreeCheck {

    static int failures;

    static ChainItem item(String name, String recipe, Type type, long factor, long multiplier) {
        return new ChainItem(name, recipe, type, factor, multiplier, Set.of(name), name + "@" + recipe + ":" + type);
    }

    static List<ChainItem> chain(boolean planksInStock) {
        List<ChainItem> g = new ArrayList<>();
        g.add(item("torch", "T", Type.RESULT, 4, 2));
        g.add(item("coal", "T", Type.INGREDIENT, 1, 2));
        g.add(item("stick", "T", Type.INGREDIENT, 1, 2));
        g.add(item("stick", "S", Type.RESULT, 4, 1));
        g.add(item("plank", "S", Type.INGREDIENT, 2, 1));
        g.add(item("plank", "P", Type.RESULT, 4, 1));
        g.add(item("log", "P", Type.INGREDIENT, 1, 1));
        if (planksInStock) g.add(item("plank", null, Type.ITEM, 1, 2));
        return g;
    }

    static void check(String what, Object got, Object want) {
        boolean ok = got.equals(want);
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : " got " + got + " want " + want));
        if (!ok) failures++;
    }

    static String rows(List<TreeStats.Row> rows) {
        List<String> out = new ArrayList<>();
        for (TreeStats.Row r : rows) out.add(r.item + " " + r.amount);
        java.util.Collections.sort(out);
        return out.toString();
    }

    static String ordered(List<TreeStats.Row> rows) {
        List<String> out = new ArrayList<>();
        for (TreeStats.Row r : rows) out.add(r.item + " " + r.amount);
        return out.toString();
    }

    static TreeSlot find(TreeLayout l, String name) {
        for (TreeSlot s : l.graph.items) if (s.ingrItem.item.equals(name)) return s;
        throw new IllegalStateException(name);
    }

    static String at(TreeLayout l, String name) {
        TreeLayout.Point p = l.pointByNode.get(find(l, name));
        return p.x + "," + p.y;
    }

    public static void main(String[] args) {
        TreeLayout l = new TreeLayout();
        l.graph.rebuild(new TreeMath(chain(false), Set.of()));
        l.recalculate(Set.of(), Set.of(), null);

        check("one root", l.graph.roots.size(), 1);
        check("five nodes", l.graph.items.size(), 5);
        check("root's children", l.graph.childrenOf.get(l.graph.roots.get(0)).size(), 2);
        check("stick is made by S", find(l, "stick").recipeId(), "S");
        check("coal is raw", find(l, "coal").prefItem == null, true);
        check("log is raw", find(l, "log").prefItem == null, true);
        check("stick needed", find(l, "stick").requestedAmount, 2L);
        check("coal needed", find(l, "coal").requestedAmount, 2L);
        check("plank needed", find(l, "plank").requestedAmount, 2L);
        check("coal still to get", find(l, "coal").leftAmount, 2L);
        check("layout torch", at(l, "torch"), "12,0");
        check("layout coal", at(l, "coal"), "0,32");
        check("layout stick", at(l, "stick"), "24,32");
        check("layout plank", at(l, "plank"), "24,64");
        check("layout log", at(l, "log"), "24,96");
        check("tree size", l.treeWidth + "x" + l.treeHeight, "42x114");

        TreeSlot stick = find(l, "stick");
        int[] moved = l.recalculate(Set.of(), Set.of(stick.ingrItem), stick);
        check("collapsed stick hides what it needs", l.treeHeight, 50);
        check("anchor did not move", moved[0] + "," + moved[1], "0,0");

        TreeLayout s = new TreeLayout();
        s.graph.rebuild(new TreeMath(chain(true), Set.of()));
        s.recalculate(Set.of(), Set.of(), null);
        check("planks in stock: resolved", find(s, "plank").leftAmount, 0L);
        check("but the recipe that would make them is still shown", find(s, "log") != null, true);
        check("nothing left to craft below the stick", s.graph.childrenNeedCraftingCache.contains(find(s, "stick")), false);
        check("the torch still needs coal", s.graph.childrenNeedCraftingCache.contains(find(s, "torch")), true);

        check("S is only there for the torches", l.graph.collectUnusedRecipes(find(l, "stick")), Set.of("S", "P"));

        TreeMath tm = new TreeMath(chain(false), Set.of());
        TreeLayout sl = new TreeLayout();
        sl.graph.rebuild(tm);
        sl.recalculate(Set.of(), Set.of(), null);
        System.out.println("--- stats");
        check("raw ingredients", rows(TreeStats.inputs(tm)), "[coal 2, log 1]");
        check("results", rows(TreeStats.outputs(tm)), "[torch 8]");
        check("leftovers", rows(TreeStats.remainders(tm)), "[plank 2, stick 2]");
        check("crafting needed, deepest recipes last", ordered(TreeStats.crafting(sl.graph, true, true)), "[torch 8, stick 4, plank 4]");
        check("handler of a recipe id", TreeStats.handlerOf("minecraft:crafting|abc"), "minecraft:crafting");

        TreeStatsLayout lay = TreeStatsLayout.compute(List.of("Ingredients", "Results"), List.of(2, 1), 300, 200, t -> t.length() * 6);
        check("labels and slots placed", lay.placed.size(), 5);
        check("content centred in a wide panel", lay.shiftX > 0, true);
        check("two sections side by side: first slot of the second one is right of the first", lay.placed.get(4).x() > lay.placed.get(2).x(), true);
        TreeStatsLayout narrow = TreeStatsLayout.compute(List.of("Ingredients"), List.of(10), 60, 60, t -> t.length() * 6);
        check("one row when the panel is short", narrow.panelHeight, 9 + 4 + 18 + 8);

        System.out.println("--- numbers");
        check("small amount plain", ReadableNumber.amount(9999), "9999");
        check("12345 is cut to the width", ReadableNumber.amount(12345), "12k");
        check("1.5M keeps its decimal", ReadableNumber.wide(1_500_000), "1.5M");
        check("10000", ReadableNumber.amount(10_000), "10k");

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
