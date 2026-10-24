import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import neiaddon.chain.ChainItem;
import java.util.ArrayList;
import java.util.List;
import neiaddon.Bookmarks;
import neiaddon.Favorites;
import neiaddon.RecipeData;
import neiaddon.RecipeLookup;
import neiaddon.RecipeShape;
import neiaddon.RecipeTree;
import neiaddon.chain.ChainDetails;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Checks the recipe-tree model (brief sections 3, 4, 7, 9) against stand-in Minecraft classes:
 *
 * <pre>
 * python3 tools/make_stubs.py /tmp/stubcheck/src
 * javac -d /tmp/stubcheck/out $(find /tmp/stubcheck/src -name '*.java') src/main/java/neiaddon/chain/*.java \
 *   src/main/java/neiaddon/{Bookmarks,RecipeData,RecipeShape,RecipeTree,RecipeLookup,Favorites}.java \
 *   src/main/java/neiaddon/RecipeHearts.java tools/ModelCheck.java
 * java -cp /tmp/stubcheck/out ModelCheck
 * </pre>
 */
public class ModelCheck {

    static int failures;

    static void expect(String what, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected " + expected + " got " + actual);
        } else {
            System.out.println("ok   " + what + " = " + actual);
        }
    }

    static ItemStack stack(String id, int count) {
        return new ItemStack(Item.of(id), count);
    }

    static RecipeShape.Slot slot(Object... alts) {
        // alts: id, count, id, count ...
        try {
            var ctor = RecipeShape.Slot.class.getDeclaredConstructor(List.class);
            ctor.setAccessible(true);
            List<RecipeShape.Alt> list = new ArrayList<>();
            for (int i = 0; i < alts.length; i += 2) {
                list.add(new RecipeShape.Alt(stack((String) alts[i], 1), (Integer) alts[i + 1]));
            }
            return ctor.newInstance(list);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static RecipeShape shape(String id, RecipeShape.Slot[] outputs, RecipeShape.Slot[] inputs) {
        RecipeShape s = new RecipeShape(id);
        for (RecipeShape.Slot o : outputs) s.outputs.add(o);
        for (RecipeShape.Slot i : inputs) s.inputs.add(i);
        return s;
    }

    static Bookmarks.Entry find(Bookmarks.Page page, String recipe, String item, int type) {
        for (Bookmarks.Entry e : page.entries) {
            if (recipe.equals(e.recipeId) && e.itemId().equals(item) && e.type.toInt() == type) return e;
        }
        throw new IllegalStateException("no entry " + recipe + "/" + item);
    }

    static void resetBookmarks() throws Exception {
        Bookmarks.pages.clear();
        Bookmarks.activePage = 0;
        Field loaded = Bookmarks.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
    }

    public static void main(String[] args) throws Exception {
        Path cfg = Path.of("/tmp/stubcheck/config/neiaddon");
        Files.createDirectories(cfg);
        Files.deleteIfExists(cfg.resolve("bookmarks.txt"));
        Files.deleteIfExists(cfg.resolve("favorites.txt"));

        // ---- section 3: six slots with one iron ingot each = 6; a tag slot keeps its options
        System.out.println("--- recipe -> bookmark items");
        RecipeShape six = shape(
            "t|six",
            new RecipeShape.Slot[] { slot("rail", 6) },
            new RecipeShape.Slot[] { slot("iron", 1), slot("iron", 1), slot("iron", 1), slot("iron", 1), slot("iron", 1),
                slot("iron", 1), slot("stick", 1) });
        RecipeData d = six.toData(null);
        expect("results", 1, d.results.size());
        expect("rail factor", 6L, d.results.get(0).factor);
        expect("ingredients", 2, d.ingredients.size());
        expect("iron factor (6 slots)", 6L, d.ingredients.get(0).factor);
        RecipeShape tag = shape(
            "t|tag",
            new RecipeShape.Slot[] { slot("chest", 1) },
            new RecipeShape.Slot[] { slot("plank_a", 4, "plank_b", 4), slot("plank_a", 4, "plank_b", 4) });
        RecipeData td = tag.toData(null);
        expect("tag factor", 8L, td.ingredients.get(0).factor);
        expect("tag options", List.of("plank_a", "plank_b"), td.ingredients.get(0).options);
        RecipeShape two = shape(
            "t|two",
            new RecipeShape.Slot[] { slot("a", 1), slot("b", 2) },
            new RecipeShape.Slot[] { slot("x", 1) });
        expect("only hovered result", 1, two.toData("b").results.size());
        expect("all results", 2, two.toData(null).results.size());

        // ---- section 4: favorites + tree
        System.out.println("--- favorites and tree");
        RecipeShape rail = shape(
            "t|rail",
            new RecipeShape.Slot[] { slot("rail", 6) },
            new RecipeShape.Slot[] { slot("iron", 6), slot("stick", 2), slot("torch", 1) });
        RecipeShape smelt = shape(
            "t|smelt",
            new RecipeShape.Slot[] { slot("iron", 1) },
            new RecipeShape.Slot[] { slot("raw", 1) });
        RecipeShape torch = shape(
            "t|torch",
            new RecipeShape.Slot[] { slot("torch", 1) },
            new RecipeShape.Slot[] { slot("stick", 1), slot("redstone", 1) });
        RecipeLookup.register(rail);
        RecipeLookup.register(smelt);
        RecipeLookup.register(torch);
        Favorites.set("iron", "t|smelt");
        Favorites.set("torch", "t|torch");
        expect("favorite of iron", "t|smelt", Favorites.recipeFor("iron"));
        Favorites.set("blaze", "t|smelt"); // same recipe -> moves the favorite
        expect("old favorite removed", null == Favorites.recipeFor("iron"), true);
        Favorites.set("iron", "t|smelt");
        expect("favorite moved to iron", "iron", Favorites.itemFor("t|smelt"));
        expect("blaze lost it", null == Favorites.recipeFor("blaze"), true);
        List<RecipeShape> tree = RecipeTree.build(rail.copy(), RecipeTree.DEPTH);
        List<String> ids = new ArrayList<>();
        for (RecipeShape t : tree) ids.add(t.id);
        expect("tree row order", List.of("t|rail", "t|smelt", "t|torch"), ids);

        // ---- save the tree as one group; section 6 numbers come out of the chain maths
        System.out.println("--- group of the tree");
        resetBookmarks();
        Bookmarks.ensureLoaded();
        int gid = RecipeTree.save(rail);
        Bookmarks.Page page = Bookmarks.page();
        expect("group made", true, gid > 0);
        expect("todo view", true, page.meta(gid).todo);
        expect("crafting chain on", true, page.meta(gid).chain);
        expect("entries", 9, page.entries.size());
        expect("first entry is the top recipe's result", "rail", page.entries.get(0).itemId());
        expect("rail row", "iron", page.entries.get(1).itemId());
        long[] want = { 6, 6, 2, 1 };
        String[] items = { "rail", "iron", "stick", "torch" };
        int[] types = { 1, 2, 2, 2 };
        for (int i = 0; i < 4; i++) {
            expect("rail/" + items[i], want[i], Bookmarks.displayAmount(page, find(page, "t|rail", items[i], types[i])));
        }
        expect("smelt/iron", 6L, Bookmarks.displayAmount(page, find(page, "t|smelt", "iron", 1)));
        expect("smelt/raw", 6L, Bookmarks.displayAmount(page, find(page, "t|smelt", "raw", 2)));
        expect("torch/torch", 1L, Bookmarks.displayAmount(page, find(page, "t|torch", "torch", 1)));
        // Ctrl + wheel on a rail item: x1 -> x2 -> x3
        Bookmarks.shiftRecipeAmount(page, gid, "t|rail", 1);
        Bookmarks.shiftRecipeAmount(page, gid, "t|rail", 1);
        expect("rail multiplier", 3L, Bookmarks.recipeMultiplier(page, "t|rail", gid));
        expect("x3 rail/rail", 18L, Bookmarks.displayAmount(page, find(page, "t|rail", "rail", 1)));
        expect("x3 rail/stick", 6L, Bookmarks.displayAmount(page, find(page, "t|rail", "stick", 2)));
        expect("x3 smelt/raw", 18L, Bookmarks.displayAmount(page, find(page, "t|smelt", "raw", 2)));
        expect("x3 torch/redstone", 3L, Bookmarks.displayAmount(page, find(page, "t|torch", "redstone", 2)));
        ChainDetails details = Bookmarks.chain(page, gid);
        expect("real multiplier of rail", 3L, details.calculated.get(find(page, "t|rail", "rail", 1)).realMultiplier);
        // Ctrl + wheel down on the group strip scales the output recipes
        Bookmarks.shiftGroupAmount(page, gid, -1);
        expect("group scaled down", 2L, Bookmarks.recipeMultiplier(page, "t|rail", gid));

        // ---- persistence round trip
        System.out.println("--- persistence");
        Bookmarks.save();
        List<String> before = Files.readAllLines(cfg.resolve("bookmarks.txt"));
        resetBookmarks();
        Bookmarks.ensureLoaded();
        Bookmarks.save();
        List<String> after = Files.readAllLines(cfg.resolve("bookmarks.txt"));
        expect("round trip identical", before, after);
        Bookmarks.Page back = Bookmarks.page();
        expect("loaded entries", 9, back.entries.size());
        expect("todo flag survives", true, back.meta(1).todo);
        expect("recipe survives", true, back.entries.get(0).isRecipe());
        expect("factor survives", 6L, back.entries.get(0).factor);

        // unknown items are kept
        System.out.println("--- unknown items");
        List<String> lines = new ArrayList<>(after);
        lines.add("item\tghostmod:thing\t5\t1\t2\tt|rail\t3\tghostmod:thing,ghostmod:other");
        Files.write(cfg.resolve("bookmarks.txt"), lines);
        resetBookmarks();
        Bookmarks.ensureLoaded();
        // ghostmod:thing is registered by the stub registry only if someone asked for it, so it is unknown here
        expect("ghost kept in memory", 1, Bookmarks.page().ghosts.size());
        Bookmarks.save();
        List<String> kept = Files.readAllLines(cfg.resolve("bookmarks.txt"));
        expect("ghost line written back", true, kept.contains(lines.get(lines.size() - 1)));

        // ---- single recipe in the default group, removal rules, half recipes
        System.out.println("--- single recipe and removal");
        resetBookmarks();
        Files.deleteIfExists(cfg.resolve("bookmarks.txt"));
        Bookmarks.ensureLoaded();
        Bookmarks.Page p2 = Bookmarks.page();
        RecipeData single = two.toData("b");
        expect("added", true, Bookmarks.addRecipe(p2, single, 1, Bookmarks.NO_GROUP));
        expect("not added twice", false, Bookmarks.addRecipe(p2, single, 1, Bookmarks.NO_GROUP));
        expect("entries (1 result + 1 ingredient)", 2, p2.entries.size());
        // the only result removes the whole recipe
        Bookmarks.removeEntry(p2, p2.entries.get(0), false);
        expect("whole recipe removed", 0, p2.entries.size());
        Bookmarks.addRecipe(p2, two.toData(null), 1, Bookmarks.NO_GROUP);
        expect("2 results + 1 ingredient", 3, p2.entries.size());
        Bookmarks.removeEntry(p2, p2.entries.get(0), false);
        expect("one of two results only removes that one", 2, p2.entries.size());
        // the remaining result + ingredient still form a recipe; remove the ingredient -> leftovers become plain items
        Bookmarks.Entry ingredient = p2.entries.get(1);
        Bookmarks.removeEntry(p2, ingredient, false);
        expect("leftover is plain", true, p2.entries.get(0).isPlain());
        expect("leftover recipe id cleared", true, p2.entries.get(0).recipeId == null);

        // ---- chat links: what is shared can be imported on another page / client
        System.out.println("--- share and import");
        resetBookmarks();
        Files.deleteIfExists(cfg.resolve("bookmarks.txt"));
        Bookmarks.ensureLoaded();
        int sgid = Bookmarks.addGroup(List.of(two.toData("b")), true, true);
        Bookmarks.Page src = Bookmarks.page();
        int groupLines = (int) src.entries.stream().filter(e -> e.groupId == sgid).count();
        String groupPayload = Bookmarks.shareGroup(src, sgid);
        Bookmarks.Entry result = src.entries.stream().filter(e -> e.groupId == sgid && e.type == ChainItem.Type.RESULT).findFirst().get();
        String recipePayload = Bookmarks.shareRecipe(src, result);
        String itemPayload = Bookmarks.shareItem(result.key.sample, 5);
        resetBookmarks();
        Files.deleteIfExists(cfg.resolve("bookmarks.txt"));
        Bookmarks.ensureLoaded();
        Bookmarks.Page dst = Bookmarks.page();
        expect("group imported", true, Bookmarks.importShared(groupPayload));
        expect("group items", groupLines, dst.entries.size());
        int newGid = dst.entries.get(0).groupId;
        expect("new group id", true, newGid > 0);
        expect("group shown as sent (chain, todo)", true, dst.metas.get(newGid).chain && dst.metas.get(newGid).todo);
        expect("recipe ids kept", true, dst.entries.stream().allMatch(e -> e.recipeId != null));
        expect("recipe imported to the default group", true, Bookmarks.importShared(recipePayload));
        expect("recipe items in default group", groupLines, (int) dst.entries.stream().filter(e -> e.groupId == Bookmarks.NO_GROUP).count());
        expect("recipe not added twice", true, Bookmarks.importShared(recipePayload));
        expect("still once", groupLines, (int) dst.entries.stream().filter(e -> e.groupId == Bookmarks.NO_GROUP).count());
        int countBefore = dst.entries.size();
        Bookmarks.importShared(itemPayload);
        Bookmarks.importShared(itemPayload);
        expect("item added once, amount summed", countBefore + 1, dst.entries.size());
        expect("item amount 10", 10L, dst.entries.stream().filter(e -> e.isPlain()).findFirst().get().amount);
        expect("garbage refused", false, Bookmarks.importShared("hello"));

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
