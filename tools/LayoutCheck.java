import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import neiaddon.BookmarkPanel;
import neiaddon.Bookmarks;
import neiaddon.RecipeData;
import neiaddon.RecipeShape;
import neiaddon.chain.ChainItem;
import neiaddon.chain.TreeLayout;
import neiaddon.chain.TreeMath;
import neiaddon.chain.TreeSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Checks the panel's NEI layout rules through reflection, against stand-in Minecraft classes. See
 * ModelCheck for how to compile (add common/src/main/java/neiaddon/BookmarkPanel.java and tools/LayoutCheck.java).
 */
public class LayoutCheck {

    static int failures;

    static void expect(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + " got " + actual);
        } else {
            System.out.println("ok   " + what + " = " + actual);
        }
    }

    static RecipeShape.Slot slot(String id, int count) throws Exception {
        var ctor = RecipeShape.Slot.class.getDeclaredConstructor(List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(List.of(new RecipeShape.Alt(new ItemStack(Item.of(id), 1), count)));
    }

    static ChainItem chainItem(String name, String recipe, ChainItem.Type type, long factor, long multiplier) {
        return new ChainItem(name, recipe, type, factor, multiplier, setOfStrings(name), recipe + ":" + name + ":" + type);
    }

    static Set<String> setOfStrings(String... values) {
        return new HashSet<>(List.of(values));
    }

    static RecipeData recipe(String id, String[] results, String[] ingredients) throws Exception {
        RecipeShape s = new RecipeShape(id);
        for (String r : results) s.outputs.add(slot(r, 1));
        for (String i : ingredients) s.inputs.add(slot(i, 1));
        return s.toData(null);
    }

    /** Lays the active page out with the given number of columns and returns "row,col:item" for every cell. */
    static List<String> layout(int columns) throws Exception {
        Field cols = BookmarkPanel.class.getDeclaredField("cols");
        cols.setAccessible(true);
        cols.setInt(null, columns);
        Field rows = BookmarkPanel.class.getDeclaredField("rows");
        rows.setAccessible(true);
        rows.setInt(null, 6);
        Method build = BookmarkPanel.class.getDeclaredMethod("buildCells", Bookmarks.Page.class);
        build.setAccessible(true);
        build.invoke(null, Bookmarks.page());
        Field cellsField = BookmarkPanel.class.getDeclaredField("cells");
        cellsField.setAccessible(true);
        List<?> cells = (List<?>) cellsField.get(null);
        List<String> out = new ArrayList<>();
        for (Object c : cells) {
            Class<?> k = c.getClass();
            Method row = k.getDeclaredMethod("row");
            Method col = k.getDeclaredMethod("col");
            Method entry = k.getDeclaredMethod("entry");
            Method first = k.getDeclaredMethod("firstOutput");
            row.setAccessible(true);
            col.setAccessible(true);
            entry.setAccessible(true);
            first.setAccessible(true);
            Bookmarks.Entry e = (Bookmarks.Entry) entry.invoke(c);
            out.add(row.invoke(c) + "," + col.invoke(c) + ":" + e.itemId() + ((Boolean) first.invoke(c) ? "*" : ""));
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("/tmp/stubcheck/config/neiaddon/bookmarks.txt"));
        Field loaded = Bookmarks.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        Bookmarks.pages.clear();
        loaded.setBoolean(null, false);
        Bookmarks.ensureLoaded();
        Bookmarks.Page page = Bookmarks.page();

        List<RecipeData> tree = List.of(
            recipe("rail", new String[] { "rail" }, new String[] { "iron", "stick", "torch" }),
            recipe("smelt", new String[] { "iron" }, new String[] { "raw" }),
            recipe("torch", new String[] { "torch" }, new String[] { "stick", "redstone" }));
        int gid = Bookmarks.addGroup(tree, true, true);

        System.out.println("--- todo view, 9 columns: one recipe per row");
        expect("rows", List.of("0,0:rail*", "0,1:iron", "0,2:stick", "0,3:torch", "1,0:iron*", "1,1:raw", "2,0:torch*",
            "2,1:stick", "2,2:redstone"), layout(9));

        System.out.println("--- todo view, 3 columns: overflow continues from the second column");
        expect("rows", List.of("0,0:rail*", "0,1:iron", "0,2:stick", "1,1:torch", "2,0:iron*", "2,1:raw", "3,0:torch*",
            "3,1:stick", "3,2:redstone"), layout(3));

        System.out.println("--- normal view of a crafting group: results only");
        page.meta(gid).todo = false;
        expect("cells", List.of("0,0:rail*", "0,1:iron*", "0,2:torch*"), layout(9));

        System.out.println("--- collapsed group: only what fits on one row");
        page.meta(gid).collapsed = true;
        expect("cells 9 cols", List.of("0,0:rail*", "0,1:iron*", "0,2:torch*"), layout(9));
        expect("cells 2 cols", List.of("0,0:rail*", "0,1:iron*"), layout(2));
        page.meta(gid).collapsed = false;

        System.out.println("--- collapsed recipes (NEI generateCollapsedRecipe)");
        page.meta(gid).todo = true;
        page.meta(gid).collapsedRecipes.add("rail");
        Bookmarks.save();
        expect("top recipe collapsed, todo view: its result, then what the whole tree needs", List.of("0,0:rail*", "0,1:raw",
            "0,2:redstone", "0,3:stick"), layout(9));
        page.meta(gid).todo = false;
        expect("top recipe collapsed, normal view", List.of("0,0:rail*"), layout(9));
        page.meta(gid).collapsedRecipes.clear();
        page.meta(gid).collapsedRecipes.add("torch");
        page.meta(gid).todo = true;
        Bookmarks.save();
        expect("recipe in the middle collapsed, todo view", List.of("0,0:rail*", "0,1:iron", "0,2:stick", "0,3:torch",
            "1,0:iron*", "1,1:raw", "2,0:torch*", "2,1:redstone", "2,2:stick"), layout(9));
        page.meta(gid).collapsedRecipes.clear();
        Bookmarks.save();
        page.meta(gid).todo = true;

        System.out.println("--- plain items: a new group starts on a new row; todo gives a plain item its own row");
        Bookmarks.add(new ItemStack(Item.of("dirt"), 1), 5);
        Bookmarks.add(new ItemStack(Item.of("stone"), 1), 5);
        page.meta(gid).todo = true;
        expect("plain items follow on a new row", List.of("0,0:rail*", "0,1:iron", "0,2:stick", "0,3:torch", "1,0:iron*",
            "1,1:raw", "2,0:torch*", "2,1:stick", "2,2:redstone", "3,0:dirt", "3,1:stone"), layout(9));
        page.meta(Bookmarks.NO_GROUP).todo = true;
        expect("todo plain items: one per row", List.of("0,0:rail*", "0,1:iron", "0,2:stick", "0,3:torch", "1,0:iron*",
            "1,1:raw", "2,0:torch*", "2,1:stick", "2,2:redstone", "3,0:dirt", "4,0:stone"), layout(9));

        System.out.println("--- collapsing: toggle, relations, removal, cleanup");
        page.meta(Bookmarks.NO_GROUP).todo = false;
        page.meta(gid).collapsedRecipes.clear();
        Bookmarks.save();
        Bookmarks.toggleCollapsedRecipe(page, gid, "smelt");
        expect("smelt collapsed", true, page.meta(gid).collapsedRecipes.contains("smelt"));
        expect("recipe in the middle runs 0 times", 0L, Bookmarks.recipeMultiplier(page, "smelt", gid));
        Bookmarks.toggleCollapsedRecipe(page, gid, "smelt");
        expect("smelt expanded", false, page.meta(gid).collapsedRecipes.contains("smelt"));
        expect("and runs once again", 1L, Bookmarks.recipeMultiplier(page, "smelt", gid));
        Bookmarks.toggleCollapsedRecipe(page, gid, "rail");
        Bookmarks.toggleCollapsedRecipe(page, gid, "torch");
        expect("a swallowed recipe toggles the collapsing one", false, page.meta(gid).collapsedRecipes.contains("rail"));
        expect("and is not collapsed itself", false, page.meta(gid).collapsedRecipes.contains("torch"));
        Bookmarks.toggleCollapsedRecipe(page, gid, "rail");
        page.meta(gid).collapsedRecipes.add("gone");
        page.normalize();
        expect("collapsed recipes that no longer exist are dropped", false, page.meta(gid).collapsedRecipes.contains("gone"));
        int before = page.entries.size();
        Bookmarks.removeSlot(page, page.entries.stream().filter(e -> "rail".equals(e.recipeId)).findFirst().get(), "rail", false);
        expect("collapsed recipe: plain remove does nothing", before, page.entries.size());
        Bookmarks.removeSlot(page, page.entries.stream().filter(e -> "rail".equals(e.recipeId)).findFirst().get(), "rail", true);
        expect("collapsed recipe: Shift removes it with the recipes it swallows", 0L,
            page.entries.stream().filter(e -> e.groupId == gid).count());

        // ---- GitHub #42: a multi-output recipe is ONE node in the tree (and unchanged in the panel)
        System.out.println("--- panel view of a multi-output recipe: unchanged, every result its own cell");
        List<RecipeData> wideGroup = List.of(
            recipe("wide", new String[] { "wax", "wand", "wheat" }, new String[] { "flint" }),
            recipe("wedge", new String[] { "wedge" }, new String[] { "wand" }));
        Bookmarks.addGroup(wideGroup, true, true);
        Set<String> wideCells = new TreeSet<>();
        for (String cell : layout(9)) {
            String item = cell.substring(cell.indexOf(':') + 1);
            if (setOfStrings("wax", "wand", "wheat", "flint", "wedge").contains(item.replace("*", ""))) {
                wideCells.add(item);
            }
        }
        // the panel still gives every result its own cell; the marker only sits on the first item of a run of one
        // recipe's items (same rule as the single-result groups above), and the second group's wand is its ingredient
        expect("the panel still lists all three results, plus the consumer's needs",
            List.copyOf(new TreeSet<>(List.of("wax*", "wand", "wheat", "flint", "wedge*"))), List.copyOf(wideCells));

        System.out.println("--- tree of a multi-output recipe: one column, its results beside its slot");
        List<ChainItem> wideChain = new ArrayList<>();
        wideChain.add(chainItem("d", "D", ChainItem.Type.RESULT, 1, 2));
        wideChain.add(chainItem("a", "D", ChainItem.Type.INGREDIENT, 1, 2));
        wideChain.add(chainItem("c", "D", ChainItem.Type.INGREDIENT, 1, 2));
        wideChain.add(chainItem("a", "P", ChainItem.Type.RESULT, 1, 1));
        wideChain.add(chainItem("b", "P", ChainItem.Type.RESULT, 1, 1));
        wideChain.add(chainItem("c", "P", ChainItem.Type.RESULT, 1, 1));
        wideChain.add(chainItem("flint", "P", ChainItem.Type.INGREDIENT, 1, 1));
        TreeLayout wide = new TreeLayout();
        wide.graph.rebuild(new TreeMath(wideChain, Set.of()));
        wide.recalculate(Set.of(), Set.of(), null);
        TreeSlot pWide = null;
        TreeSlot flintWide = null;
        int pNodes = 0;
        for (TreeSlot node : wide.graph.items) {
            if ("P".equals(node.recipeId())) {
                pWide = node;
                pNodes++;
            }
            if (node.prefItem == null) {
                flintWide = node;
            }
        }
        expect("one node for P, not one per wanted result", 1, pNodes);
        expect("D, P and the flint", 3, wide.graph.items.size());
        expect("the P node shows all three results", 3, pWide.outputs.size());
        expect("P sits in the single column under D", 32, wide.pointByNode.get(pWide).y);
        expect("the flint hangs under P's main slot, not under another copy",
            wide.pointByNode.get(pWide).x, wide.pointByNode.get(flintWide).x);
        expect("one column, three result slots wide", 54, wide.treeWidth);
        expect("the extra results do not add a row", 82, wide.treeHeight);
        // the "+" collapse: the recipe id goes into TreeMath's collapsed set, so the node under D has no demand
        // below it. NEI's maths also hoists a collapsed recipe into the roots (as before: 4 roots, 9 nodes), and
        // there too it is now ONE root carrying all results instead of one per wanted result (2 roots, 5 nodes);
        // the hoisted root carries three result slots, and the wide node beside D keeps showing all three results
        wide.graph.rebuild(new TreeMath(wideChain, setOfStrings("P")));
        wide.recalculate(Set.of(), Set.of(), null);
        expect("collapsed: D plus one hoisted root, not one per result", 2, wide.graph.roots.size());
        expect("collapsed: D, the node under it, the hoisted root and its flint", 5, wide.graph.items.size());
        expect("collapsed: the P nodes keep their three result slots", 186, wide.treeWidth);
        expect("collapsed: no demand drawn below the node under D", 50, wide.treeHeight);

        System.out.println("--- two bookmarked multi-output recipes: the wide one pushes the next root aside");
        List<ChainItem> twoWide = new ArrayList<>();
        twoWide.add(chainItem("a", "P", ChainItem.Type.RESULT, 1, 3));
        twoWide.add(chainItem("b", "P", ChainItem.Type.RESULT, 1, 3));
        twoWide.add(chainItem("c", "P", ChainItem.Type.RESULT, 1, 3));
        twoWide.add(chainItem("flint", "P", ChainItem.Type.INGREDIENT, 1, 3));
        twoWide.add(chainItem("m", "Q", ChainItem.Type.RESULT, 1, 2));
        twoWide.add(chainItem("n", "Q", ChainItem.Type.RESULT, 1, 2));
        twoWide.add(chainItem("o", "Q", ChainItem.Type.RESULT, 1, 2));
        twoWide.add(chainItem("sand", "Q", ChainItem.Type.INGREDIENT, 1, 2));
        TreeLayout both = new TreeLayout();
        both.graph.rebuild(new TreeMath(twoWide, Set.of()));
        both.recalculate(Set.of(), Set.of(), null);
        TreeSlot qRoot = null;
        for (TreeSlot node : both.graph.roots) {
            if ("Q".equals(node.recipeId())) {
                qRoot = node;
            }
        }
        expect("two roots, two columns", 2, both.graph.roots.size());
        // P's main slot is 18 and its two extra results reach x = 53; the contour edge the roots shift by is the
        // extras width (36), so NEI's root spacing (36) puts Q's main slot at x = 72
        expect("Q's main slot clears P's extra results", 72, both.pointByNode.get(qRoot).x);
        expect("width covers Q's own extra results too", 126, both.treeWidth);

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
