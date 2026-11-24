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
 * javac -d /tmp/tcheck common/src/main/java/neiaddon/chain/*.java tools/TreeCheck.java
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

    /** The node's result slots in order, as item names (issue #42 follow-up: which one sits leftmost). */
    static String outOrder(TreeLayout l, String name) {
        List<String> out = new ArrayList<>();
        for (TreeSlot.Output o : find(l, name).outputs()) out.add(o.shown().item);
        return out.toString();
    }

    /** The node's result slots as sorted "item=amount" pairs: pairs, so the order cannot make it pass. */
    static String outAmounts(TreeLayout l, String name) {
        List<String> out = new ArrayList<>();
        for (TreeSlot.Output o : find(l, name).outputs()) out.add(o.shown().item + "=" + o.requestedAmount);
        java.util.Collections.sort(out);
        return out.toString();
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

        // #39: the tree's chain items take a picked tag slot as exactly the chosen item, without the
        // other items the slot accepts: a sub-recipe of another accepted item (the favorited one from before the
        // pick) then no longer supplies, and no longer shows on, the slot.
        List<ChainItem> picked = new ArrayList<>();
        picked.add(new ChainItem("mix", "M", Type.RESULT, 1, 2, Set.of("mix"), "mix@M"));
        picked.add(new ChainItem("crystal", "M", Type.INGREDIENT, 2, 2, Set.of("crystal"), "slot"));
        picked.add(new ChainItem("charged", "G", Type.RESULT, 1, 1, Set.of("charged"), "charged@G"));
        picked.add(new ChainItem("pearl", "G", Type.INGREDIENT, 1, 1, Set.of("pearl"), "pearl@G"));
        TreeMath pm = new TreeMath(picked, Set.of());
        TreeLayout pl = new TreeLayout();
        pl.graph.rebuild(pm);
        pl.recalculate(Set.of(), Set.of(), null);
        check("picked slot: the old favorite recipe of another accepted item does not supply it",
            find(pl, "crystal").prefItem == null, true);
        check("picked slot: still asks for the chosen item", find(pl, "crystal").requestedAmount, 4L);

        // the same slot with the accepted items left in the maths (the old, pre-fix shape of the chain items)
        // keeps the loose link, so that link is what dropping the options breaks
        List<ChainItem> loose = new ArrayList<>();
        loose.add(new ChainItem("mix", "M", Type.RESULT, 1, 2, Set.of("mix"), "mix@M"));
        loose.add(new ChainItem("crystal", "M", Type.INGREDIENT, 2, 2, Set.of("crystal", "charged"), "slot"));
        loose.add(new ChainItem("charged", "G", Type.RESULT, 1, 1, Set.of("charged"), "charged@G"));
        loose.add(new ChainItem("pearl", "G", Type.INGREDIENT, 1, 1, Set.of("pearl"), "pearl@G"));
        TreeMath lm = new TreeMath(loose, Set.of());
        TreeLayout ll = new TreeLayout();
        ll.graph.rebuild(lm);
        ll.recalculate(Set.of(), Set.of(), null);
        check("with the accepted items still in the maths the old sub-recipe links (the pre-fix shape)",
            find(ll, "crystal").prefItem != null, true);

        // #42 follow-up: a multi-result recipe entered on one of its results shows that result at the
        // node's leftmost slot (the tree screen passes it in as the recipe's chosen result, from the favorites);
        // the other results keep the recipe's order, and without a chosen result the recipe's order stands.
        List<ChainItem> multi = new ArrayList<>();
        multi.add(item("plate", "X", Type.RESULT, 2, 1));
        multi.add(item("ball", "X", Type.RESULT, 1, 1));
        multi.add(item("lens", "X", Type.RESULT, 1, 1));
        multi.add(item("dust", "X", Type.INGREDIENT, 1, 1));

        TreeMath nm = new TreeMath(multi, Set.of());
        TreeLayout nl = new TreeLayout();
        nl.graph.rebuild(nm);
        nl.recalculate(Set.of(), Set.of(), null);
        check("multi-result node with no chosen result: the recipe's own order", outOrder(nl, "plate"), "[plate, ball, lens]");

        TreeMath em = new TreeMath(multi, Set.of());
        em.setChosenOutputs(java.util.Map.of("X", "lens"));
        TreeLayout el = new TreeLayout();
        el.graph.rebuild(em);
        el.recalculate(Set.of(), Set.of(), null);
        check("entered on the third result: it shows leftmost, the others keep their order", outOrder(el, "plate"),
            "[lens, plate, ball]");
        check("the move keeps every result its own amounts", outAmounts(el, "plate"), outAmounts(nl, "plate"));

        TreeMath fm = new TreeMath(multi, Set.of());
        fm.setChosenOutputs(java.util.Map.of("X", "plate"));
        TreeLayout fl = new TreeLayout();
        fl.graph.rebuild(fm);
        fl.recalculate(Set.of(), Set.of(), null);
        check("entered on the first result: the order does not change", outOrder(fl, "plate"), "[plate, ball, lens]");

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
