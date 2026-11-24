import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import neiaddon.chain.ChainItem;
import java.util.ArrayList;
import java.util.List;
import neiaddon.AddonConfig;
import neiaddon.Bookmarks;
import neiaddon.Favorites;
import neiaddon.RecipeData;
import neiaddon.RecipeLookup;
import neiaddon.RecipeShape;
import neiaddon.RecipeTree;
import neiaddon.TagDefaults;
import neiaddon.chain.ChainDetails;
import neiaddon.chain.TreeGraph;
import neiaddon.chain.TreeMath;
import neiaddon.chain.TreeSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Checks the recipe-tree model against stand-in Minecraft classes:
 *
 * <pre>
 * python3 tools/make_stubs.py /tmp/stubcheck/src
 * cp -R tools/stubs1201/src/. /tmp/stubcheck/src/    # 1.20.1 shapes over the 26.x ones make_stubs.py writes
 * javac -nowarn -d /tmp/stubcheck/out -sourcepath /tmp/stubcheck/src \
 *   common/src/main/java/neiaddon/chain/*.java \
 *   common/src/main/java/neiaddon/{Bookmarks,RecipeData,RecipeShape,RecipeTree,Favorites,TagDefaults}.java \
 *   tools/ModelCheck.java
 * java -cp /tmp/stubcheck/out ModelCheck
 * </pre>
 *
 * <p>The real RecipeLookup and RecipeHearts stay out of the compile: tools/stubs1201 has a small stand-in store for
 * RecipeLookup (registered shapes, found by id; one producing recipe per item), and RecipeHearts needs too many game
 * classes to stub here.
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

    static ChainItem chainItem(String name, String recipe, ChainItem.Type type, long factor, long multiplier) {
        return new ChainItem(name, recipe, type, factor, multiplier, setOfStrings(name), recipe + ":" + name + ":" + type);
    }

    static java.util.Set<String> setOfStrings(String... s) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String v : s) out.add(v);
        return out;
    }

    static void resetBookmarks() throws Exception {
        Bookmarks.pages.clear();
        Bookmarks.activePage = 0;
        Field loaded = Bookmarks.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
    }

    static void resetTagDefaults() throws Exception {
        Field loaded = TagDefaults.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
        Field map = TagDefaults.class.getDeclaredField("defaults");
        map.setAccessible(true);
        ((java.util.Map<?, ?>) map.get(null)).clear();
    }

    static List<String> idsOf(List<RecipeShape> tree) {
        List<String> ids = new ArrayList<>();
        for (RecipeShape t : tree) ids.add(t.id);
        return ids;
    }

    public static void main(String[] args) throws Exception {
        Path cfg = Path.of("/tmp/stubcheck/config/neiaddon");
        Files.createDirectories(cfg);
        Files.deleteIfExists(cfg.resolve("bookmarks.txt"));
        Files.deleteIfExists(cfg.resolve("favorites.txt"));
        Files.deleteIfExists(cfg.resolve("tagdefaults.txt"));

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
        List<RecipeShape> tree = RecipeTree.build(rail.copy(), AddonConfig.FAVORITES_DEPTH.get());
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

        // ---- crafting tree of a multi-output recipe (GitHub #42): one column, all its results at its node
        System.out.println("--- crafting tree of a multi-output recipe");
        // recipe P makes a, b and c out of flint; recipe D (the group's top, runs x2) wants a and c
        List<ChainItem> moGroup = new ArrayList<>();
        moGroup.add(chainItem("d", "D", ChainItem.Type.RESULT, 1, 2));
        moGroup.add(chainItem("a", "D", ChainItem.Type.INGREDIENT, 1, 2));
        moGroup.add(chainItem("c", "D", ChainItem.Type.INGREDIENT, 1, 2));
        moGroup.add(chainItem("a", "P", ChainItem.Type.RESULT, 1, 1));
        moGroup.add(chainItem("b", "P", ChainItem.Type.RESULT, 1, 1));
        moGroup.add(chainItem("c", "P", ChainItem.Type.RESULT, 1, 1));
        moGroup.add(chainItem("flint", "P", ChainItem.Type.INGREDIENT, 1, 1));
        TreeGraph moTree = new TreeGraph();
        moTree.rebuild(new TreeMath(moGroup, setOfStrings()));
        expect("D, P and the flint: one node for P, not one per wanted result", 3, moTree.items.size());
        expect("one child under the top recipe", 1, moTree.childrenOf.get(moTree.roots.get(0)).size());

        // a multi-output recipe bookmarked on its own: one root, not one per result (the reporter's six columns)
        List<ChainItem> moTop = new ArrayList<>();
        moTop.add(chainItem("a", "P", ChainItem.Type.RESULT, 1, 3));
        moTop.add(chainItem("b", "P", ChainItem.Type.RESULT, 1, 3));
        moTop.add(chainItem("c", "P", ChainItem.Type.RESULT, 1, 3));
        moTop.add(chainItem("flint", "P", ChainItem.Type.INGREDIENT, 1, 3));
        TreeGraph moTopTree = new TreeGraph();
        moTopTree.rebuild(new TreeMath(moTop, setOfStrings()));
        expect("one root for the bookmarked multi-output recipe", 1, moTopTree.roots.size());
        expect("one flint below it", 2, moTopTree.items.size());

        // the merged nodes carry all of the recipe's results at the one node (issue #42)
        TreeSlot dTop = moTree.roots.get(0);
        TreeSlot pNode = moTree.childrenOf.get(dTop).get(0);
        expect("the P node shows all three results", 3, pNode.outputs.size());
        expect("the P node asks for a and c, and runs twice", 2L, pNode.multiplier);
        long pWanted = 0;
        long pMade = 0;
        for (TreeSlot.Output o : pNode.outputs) {
            pWanted += o.requestedAmount;
            pMade += o.leftAmount;
        }
        expect("the wanted outputs are the node's demand", pNode.requestedAmount, pWanted);
        expect("nothing at P is still to be made twice over", pNode.leftAmount, pMade);
        expect("the flint child wants what two runs of P use", 2L,
            moTree.childrenOf.get(pNode).get(0).requestedAmount);
        TreeSlot.Output pUnused = null;
        for (TreeSlot.Output o : pNode.outputs) {
            if (o.shown().item.equals("b")) {
                pUnused = o;
            }
        }
        expect("b, wanted by nothing, is still shown at P", pUnused != null, true);
        expect("b's share is zero", pUnused != null && pUnused.requestedAmount == 0 && pUnused.leftAmount == 0, true);

        TreeSlot topRoot = moTopTree.roots.get(0);
        expect("the bookmarked root shows all three results", 3, topRoot.outputs.size());
        expect("the bookmarked root runs three times", 3L, topRoot.multiplier);
        // three runs of P, one flint per run: one column says 3 where the old three columns said 3 + 3 + 3
        expect("the flint below the bookmarked root wants three, not three columns of it", 3L,
            moTopTree.childrenOf.get(topRoot).get(0).requestedAmount);

        // ---- tag defaults (#39): the saved default comes before the favorite, and only while its item
        // is still one the tag accepts
        System.out.println("--- tag defaults");
        resetTagDefaults();
        RecipeShape bridge = shape(
            "t|bridge",
            new RecipeShape.Slot[] { slot("gate", 1) },
            new RecipeShape.Slot[] { slot("plank_b", 4, "plank_a", 4) });
        RecipeShape gate = shape(
            "t|gate",
            new RecipeShape.Slot[] { slot("gate", 1) },
            new RecipeShape.Slot[] { slot("plank_b", 4, "plank_a", 4) });
        RecipeShape planksA = shape(
            "t|plank_a",
            new RecipeShape.Slot[] { slot("plank_a", 4) },
            new RecipeShape.Slot[] { slot("log", 1) });
        RecipeShape planksB = shape(
            "t|plank_b",
            new RecipeShape.Slot[] { slot("plank_b", 4) },
            new RecipeShape.Slot[] { slot("log", 1) });
        for (RecipeShape s : java.util.Arrays.asList(bridge, gate, planksA, planksB)) RecipeLookup.register(s);
        Favorites.set("plank_b", "t|plank_b");
        Favorites.set("plank_a", "t|plank_a");
        List<String> planks = List.of("plank_b", "plank_a");
        expect("same accepted items, same tag key", TagDefaults.keyFor(planks),
            TagDefaults.keyFor(List.of("plank_a", "plank_b")));
        // the default names the item the favorite would not have picked, and wins: that is the point of the feature
        TagDefaults.setDefault(planks, "plank_a");
        RecipeShape bc = bridge.copy();
        expect("default chosen over the favorite", List.of("t|bridge", "t|plank_a"),
            idsOf(RecipeTree.build(bc, AddonConfig.FAVORITES_DEPTH.get())));
        expect("the slot's item is the default's", "plank_a", bc.inputs.get(0).alts.get(bc.inputs.get(0).active).id());
        // the same tag in another recipe gets the same default (the issue's two wrench recipes)
        RecipeShape gc = gate.copy();
        expect("the default applies to every recipe with the tag", List.of("t|gate", "t|plank_a"),
            idsOf(RecipeTree.build(gc, AddonConfig.FAVORITES_DEPTH.get())));
        // a default naming an item the tag no longer accepts is ignored, and the favorite decides again
        TagDefaults.setDefault(planks, "mystic:cobblestone");
        RecipeShape gc2 = gate.copy();
        expect("default outside the options ignored", List.of("t|gate", "t|plank_b"),
            idsOf(RecipeTree.build(gc2, AddonConfig.FAVORITES_DEPTH.get())));
        expect("the favorite item picked again", "plank_b", gc2.inputs.get(0).alts.get(gc2.inputs.get(0).active).id());
        // the store: a plain line survives a reload, broken lines are ignored
        Path tagFile = cfg.resolve("tagdefaults.txt");
        expect("store file written", true, Files.isRegularFile(tagFile));
        List<String> tagLines = new ArrayList<>(Files.readAllLines(tagFile));
        tagLines.add(TagDefaults.keyFor(List.of("zz1", "zz2")) + "\tno colon id");
        tagLines.add(TagDefaults.keyFor(List.of("q1", "q2")));
        Files.write(tagFile, tagLines);
        resetTagDefaults();
        expect("the default survives the reload", "mystic:cobblestone", TagDefaults.defaultFor(planks));
        expect("a line with an invalid id ignored", null == TagDefaults.defaultFor(List.of("zz1", "zz2")), true);
        expect("a line without a tab ignored", null == TagDefaults.defaultFor(List.of("q1", "q2")), true);

        if (failures > 0) {
            System.out.println(failures + " FAILURE(S)");
            System.exit(1);
        }
        System.out.println("ALL OK");
    }
}
