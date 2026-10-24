import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import neiaddon.BookmarkPanel;
import neiaddon.Bookmarks;
import neiaddon.RecipeData;
import neiaddon.RecipeShape;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Checks the panel's NEI layout rules (brief section 7) through reflection, against stand-in Minecraft classes. See
 * ModelCheck for how to compile (add src/main/java/neiaddon/BookmarkPanel.java and tools/LayoutCheck.java).
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

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
