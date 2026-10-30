package neiaddon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import neiaddon.chain.ChainDetails;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ChainMath;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neiaddon.platform.Platform;

/**
 * NEI-style bookmarks: several pages ("namespaces"), each an ordered list of bookmarked items. Consecutive items
 * sharing a group id form a group (drawn as a bracket beside their rows, like NEI). Group id 0 is "no group".
 *
 * <p>
 * An item is either plain (what the player has / wants) or part of a recipe: a result or an ingredient of the recipe
 * {@link Entry#recipeId}. For recipe items {@code amount = factor * multiplier}. In a group with the crafting chain on,
 * {@link #chain} works out how often every recipe has to run (see {@code neiaddon.chain}).
 *
 * <p>
 * Saved to {@code config/neiaddon/bookmarks.txt}. Only the item id is saved (not item components). Lines of items
 * from mods that are not installed are kept and written back unchanged.
 *
 * <p>
 * Recipe-tree handling is ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class Bookmarks {

    public static final int NO_GROUP = 0;

    public static final class Entry {

        /** Null only for ghosts (lines of items that are not installed), which never appear in {@link Page#entries}. */
        public Calculator.StackKey key;
        /** The item id as written in the file. */
        private final String rawId;
        public long amount;
        public int groupId;
        public ChainItem.Type type = ChainItem.Type.ITEM;
        /** Null for plain items. */
        public String recipeId;
        /** Items one run of the recipe makes (result) or uses (ingredient); 1 for plain items. */
        public long factor = 1;
        /** Registry ids of every item the ingredient slot accepts (tags); empty for plain items and results. */
        public List<String> options = List.of();

        public Entry(Calculator.StackKey key, long amount, int groupId) {
            this(key, null, amount, groupId);
        }

        private Entry(Calculator.StackKey key, String rawId, long amount, int groupId) {
            this.key = key;
            this.rawId = rawId;
            this.amount = amount;
            this.groupId = groupId;
        }

        public static Entry ghost(String rawId, long amount, int groupId) {
            return new Entry(null, rawId, amount, groupId);
        }

        public boolean isRecipe() {
            return recipeId != null && type != ChainItem.Type.ITEM;
        }

        public boolean isPlain() {
            return !isRecipe();
        }

        public String itemId() {
            return key != null ? RecipeData.idOf(key.sample) : rawId;
        }

        /** Runs of the recipe this item stands for. */
        public long multiplier() {
            return factor > 0 ? amount / factor : 0;
        }

        public boolean sameRecipe(String recipeId, int groupId) {
            return isRecipe() && this.groupId == groupId && this.recipeId.equals(recipeId);
        }

        ChainItem toChain() {
            if (isRecipe()) {
                return new ChainItem(itemId(), recipeId, type, factor, multiplier(), new LinkedHashSet<>(options), this);
            }
            return new ChainItem(itemId(), null, ChainItem.Type.ITEM, 1, amount, null, this);
        }
    }

    public static final class GroupMeta {

        public boolean collapsed;
        /** Crafting chain mode (green bracket, calculated amounts, raw materials in the tooltip). */
        public boolean chain;
        /** NEI's "todo list" view mode: one recipe (or one plain item) per row. */
        public boolean todo;
        /** Recipes shown as one merged block in a crafting chain (NEI's collapsedRecipes). */
        public final Set<String> collapsedRecipes = new LinkedHashSet<>();
    }

    /** Separates recipe ids in a file line (they can hold any printable character). */
    private static final String ID_SEPARATOR = "\u001f";

    private static String joinRecipes(Set<String> recipes) {
        return recipes.stream()
            .map(Bookmarks::clean)
            .collect(java.util.stream.Collectors.joining(ID_SEPARATOR));
    }

    private static void readRecipes(GroupMeta meta, String text) {
        meta.collapsedRecipes.clear();
        if (text == null || text.isEmpty()) return;
        for (String id : text.split(ID_SEPARATOR)) {
            if (!id.isEmpty()) meta.collapsedRecipes.add(id);
        }
    }

    public static final class Page {

        public final List<Entry> entries = new ArrayList<>();
        /** Lines of items that are not installed; kept so saving does not lose them. */
        public final List<Entry> ghosts = new ArrayList<>();
        public final Map<Integer, GroupMeta> metas = new HashMap<>();

        public GroupMeta meta(int groupId) {
            return metas.computeIfAbsent(groupId, k -> new GroupMeta());
        }

        public int nextGroupId() {
            int max = 0;
            for (Entry e : entries) max = Math.max(max, e.groupId);
            for (Entry e : ghosts) max = Math.max(max, e.groupId);
            for (int id : metas.keySet()) max = Math.max(max, id);
            return max + 1;
        }

        public boolean isEmpty() {
            return entries.isEmpty() && ghosts.isEmpty();
        }

        public boolean hasRecipes(int groupId) {
            for (Entry e : entries) if (e.groupId == groupId && e.isRecipe()) return true;
            return false;
        }

        /**
         * Keeps items of one group together and the items of one recipe together (results before ingredients), drops
         * the metadata of groups that no longer exist and turns half recipes (no results or no ingredients left) into
         * plain items.
         */
        public void normalize() {
            List<Entry> out = new ArrayList<>();
            List<Entry> rest = new ArrayList<>(entries);
            while (!rest.isEmpty()) {
                Entry first = rest.remove(0);
                List<Entry> run = new ArrayList<>();
                run.add(first);
                if (first.groupId != NO_GROUP) {
                    List<Entry> same = new ArrayList<>();
                    for (Entry e : rest) if (e.groupId == first.groupId) same.add(e);
                    rest.removeAll(same);
                    run.addAll(same);
                } else {
                    while (!rest.isEmpty() && rest.get(0).groupId == NO_GROUP) run.add(rest.remove(0));
                }
                out.addAll(groupRecipes(run));
            }
            entries.clear();
            entries.addAll(out);
            cleanHalfRecipes();
            metas.keySet()
                .removeIf(
                    id -> id != NO_GROUP && entries.stream()
                        .noneMatch(e -> e.groupId == id)
                        && ghosts.stream()
                            .noneMatch(e -> e.groupId == id));
            // NEI: group.collapsedRecipes.removeIf(recipeId -> !recipeState.containsKey(recipeId))
            metas.forEach((id, m) -> m.collapsedRecipes.removeIf(r -> entries.stream().noneMatch(e -> e.sameRecipe(r, id))));
        }

        private static List<Entry> groupRecipes(List<Entry> run) {
            List<Entry> out = new ArrayList<>();
            Set<String> done = new HashSet<>();
            for (Entry e : run) {
                if (!e.isRecipe()) {
                    out.add(e);
                } else if (done.add(e.recipeId)) {
                    for (Entry x : run) {
                        if (x.isRecipe() && x.recipeId.equals(e.recipeId) && x.type == ChainItem.Type.RESULT) out.add(x);
                    }
                    for (Entry x : run) {
                        if (x.isRecipe() && x.recipeId.equals(e.recipeId) && x.type == ChainItem.Type.INGREDIENT) out.add(x);
                    }
                }
            }
            return out;
        }

        private void cleanHalfRecipes() {
            Map<String, boolean[]> seen = new HashMap<>();
            for (Entry e : entries) {
                if (e.isRecipe()) {
                    boolean[] f = seen.computeIfAbsent(e.groupId + "|" + e.recipeId, k -> new boolean[2]);
                    f[e.type == ChainItem.Type.RESULT ? 0 : 1] = true;
                }
            }
            for (Entry e : ghosts) {
                if (e.isRecipe()) {
                    boolean[] f = seen.get(e.groupId + "|" + e.recipeId);
                    if (f != null) f[e.type == ChainItem.Type.RESULT ? 0 : 1] = true;
                }
            }
            for (Entry e : entries) {
                if (e.isRecipe()) {
                    boolean[] f = seen.get(e.groupId + "|" + e.recipeId);
                    if (!f[0] || !f[1]) {
                        e.type = ChainItem.Type.ITEM;
                        e.recipeId = null;
                        e.factor = 1;
                        e.options = List.of();
                    }
                }
            }
        }
    }

    /**
     * A snapshot of one group (or the ungrouped items) of a page, handed to the calculator. Edit {@link #items} and
     * call {@link #commit()} to write the changes back. Only plain items are written back; the results of the group's
     * output recipes are shown as read-only wishes.
     */
    public static final class Group {

        public final Page page;
        public final int groupId;
        public String name;
        public final Map<Calculator.StackKey, Long> items = new LinkedHashMap<>();
        private final Set<Calculator.StackKey> derived = new HashSet<>();

        public Group(Page page, int groupId, String name) {
            this.page = page;
            this.groupId = groupId;
            this.name = name;
            for (Entry e : page.entries) {
                if (e.groupId == groupId && e.isPlain()) items.merge(e.key, e.amount, Long::sum);
            }
            ChainDetails details = page.hasRecipes(groupId) ? computeChain(page, groupId) : null;
            if (details != null) {
                for (Entry e : page.entries) {
                    ChainDetails.Calc c = e.groupId == groupId && e.isRecipe() ? details.calculated.get(e) : null;
                    if (c != null && c.type == ChainDetails.Calculated.RESULT && !items.containsKey(e.key)) {
                        items.put(e.key, c.calculatedAmount());
                        derived.add(e.key);
                    }
                }
            }
        }

        public void commit() {
            int insertAt = page.entries.size();
            for (int i = 0; i < page.entries.size(); i++) {
                if (page.entries.get(i).groupId == groupId) {
                    insertAt = Math.min(insertAt, i);
                }
            }
            page.entries.removeIf(e -> e.groupId == groupId && e.isPlain() && !items.containsKey(e.key));
            Map<Calculator.StackKey, Long> pending = new LinkedHashMap<>(items);
            pending.keySet()
                .removeAll(derived);
            for (Entry e : page.entries) {
                if (e.groupId == groupId && e.isPlain() && pending.containsKey(e.key)) {
                    e.amount = pending.remove(e.key);
                }
            }
            for (Map.Entry<Calculator.StackKey, Long> p : pending.entrySet()) {
                page.entries.add(Math.min(insertAt, page.entries.size()), new Entry(p.getKey(), p.getValue(), groupId));
            }
            page.normalize();
            save();
        }
    }

    public static final List<Page> pages = new ArrayList<>();
    public static int activePage;
    private static boolean loaded;
    private static final Map<Page, Map<Integer, ChainDetails>> chainCache = new IdentityHashMap<>();

    private Bookmarks() {}

    private static Path loadedFile;

    private static Path file() {
        String key = AddonConfig.BM_WORLD_SPECIFIC.get() ? McCompat.worldKey() : null;
        String name = key == null ? "bookmarks.txt" : "bookmarks-" + key.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt";
        return Platform.get().configDir()
            .resolve("neiaddon")
            .resolve(name);
    }

    private static String clean(String s) {
        return s.replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ');
    }

    public static void ensureLoaded() {
        Path file = file();
        if (loaded && file.equals(loadedFile)) return;
        if (loaded) {
            // another world (or the setting changed): start from that list
            pages.clear();
            activePage = 0;
            chainCache.clear();
        }
        loaded = true;
        loadedFile = file;
        try {
            if (Files.exists(file)) {
                Page current = null;
                int legacyGroup = 0;
                int currentGroup = NO_GROUP;
                for (String line : Files.readAllLines(file)) {
                    String[] p = line.split("\t", -1);
                    if (p.length >= 1 && p[0].equals("page")) {
                        current = new Page();
                        pages.add(current);
                        currentGroup = NO_GROUP;
                    } else if (p.length >= 4 && p[0].equals("meta") && current != null) {
                        GroupMeta m = current.meta(Integer.parseInt(p[1]));
                        m.collapsed = Boolean.parseBoolean(p[2]);
                        m.chain = Boolean.parseBoolean(p[3]);
                        if (p.length >= 5) m.todo = Boolean.parseBoolean(p[4]);
                        if (p.length >= 6) readRecipes(m, p[5]);
                    } else if (p.length >= 4 && p[0].equals("item") && current != null) {
                        readItem(current, p, Integer.parseInt(p[3]));
                    } else if (p.length >= 3 && p[0].equals("group")) {
                        // version 1 file: one group per "group" line
                        if (current == null) {
                            current = new Page();
                            pages.add(current);
                        }
                        currentGroup = ++legacyGroup;
                        current.meta(currentGroup).collapsed = Boolean.parseBoolean(p[2]);
                    } else if (p.length >= 3 && p[0].equals("item") && current != null) {
                        readItem(current, p, currentGroup);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not read bookmarks", e);
        }
        if (pages.isEmpty()) pages.add(new Page());
        for (Page p : pages) p.normalize();
        activePage = Math.max(0, Math.min(activePage, pages.size() - 1));
    }

    /** {@code item id amount group [type recipeId factor options]}; unknown items become ghosts. */
    private static void readItem(Page page, String[] p, int gid) {
        long amount = parseAmount(p[2]);
        ChainItem.Type type = ChainItem.Type.ITEM;
        String recipeId = null;
        long factor = 1;
        List<String> options = List.of();
        if (p.length >= 7) {
            type = ChainItem.Type.fromInt((int) parseLong(p[4], 0));
            recipeId = p[5].isEmpty() || p[5].equals("-") ? null : p[5];
            factor = Math.max(1, parseLong(p[6], 1));
            if (p.length >= 8 && !p[7].isEmpty()) options = Arrays.asList(p[7].split(","));
        }
        if (recipeId == null) {
            type = ChainItem.Type.ITEM;
            factor = 1;
            options = List.of();
        }
        Entry entry;
        ItemStack parsed = FluidStacks.stackOf(p[1]);
        if (!parsed.isEmpty()) {
            entry = new Entry(new Calculator.StackKey(parsed), amount, gid);
            page.entries.add(entry);
        } else {
            entry = Entry.ghost(p[1], amount, gid);
            page.ghosts.add(entry);
        }
        entry.type = type;
        entry.recipeId = recipeId;
        entry.factor = factor;
        entry.options = options;
    }

    private static long parseAmount(String s) {
        return Math.max(0, parseLong(s, 1));
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void save() {
        chainCache.clear();
        Path file = loadedFile != null ? loadedFile : file();
        List<String> lines = new ArrayList<>();
        for (Page page : pages) {
            if (page.isEmpty()) continue;
            lines.add("page");
            page.metas.forEach((id, m) -> lines.add("meta\t" + id + "\t" + m.collapsed + "\t" + m.chain + "\t" + m.todo + "\t" + joinRecipes(m.collapsedRecipes)));
            for (Entry e : page.entries) lines.add(line(e));
            for (Entry e : page.ghosts) lines.add(line(e));
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines);
        } catch (IOException ex) {
            NeiAddon.LOGGER.warn("Could not save bookmarks", ex);
        }
    }

    private static String line(Entry e) {
        return line(e, e.groupId);
    }

    private static String line(Entry e, int groupId) {
        return "item\t" + clean(e.itemId()) + "\t" + e.amount + "\t" + groupId + "\t" + e.type.toInt() + "\t"
            + (e.recipeId == null ? "-" : clean(e.recipeId)) + "\t" + e.factor + "\t" + String.join(",", e.options);
    }

    public static Page page() {
        ensureLoaded();
        activePage = Math.max(0, Math.min(activePage, pages.size() - 1));
        return pages.get(activePage);
    }

    /** The calculator's view of the ungrouped items of the active page. */
    public static Group activeGroup() {
        return new Group(page(), NO_GROUP, "Bookmarks");
    }

    public static Group group(Page page, int groupId) {
        return new Group(page, groupId, groupId == NO_GROUP ? "Bookmarks" : "Group " + groupId);
    }

    /** Adds an item to the active page. An item already on the page just gets its amount increased. */
    public static void add(ItemStack stack, long amount) {
        add(stack, amount, NO_GROUP);
    }

    /** Adds an item to a group of the active page (NO_GROUP = ungrouped, appended at the end). */
    public static void add(ItemStack stack, long amount, int groupId) {
        if (stack.isEmpty()) return;
        Page page = page();
        Calculator.StackKey key = new Calculator.StackKey(stack);
        for (Entry e : page.entries) {
            if (e.isPlain() && e.key.equals(key)) {
                e.amount += amount;
                save();
                return;
            }
        }
        page.entries.add(insertIndex(page, groupId), new Entry(key, amount, groupId));
        save();
    }

    private static int insertIndex(Page page, int groupId) {
        int at = page.entries.size();
        if (groupId != NO_GROUP) {
            for (int i = 0; i < page.entries.size(); i++) {
                if (page.entries.get(i).groupId == groupId) at = i + 1;
            }
        }
        return at;
    }

    public static void remove(Page page, Entry entry) {
        page.entries.remove(entry);
        page.normalize();
        save();
    }

    // ---------------------------------------------------------------- pages (NEI "namespaces")

    /**
     * Like NEI's {@code BookmarkStorage.getNamespaceSize}: there is always one empty page after the last used one, so
     * there is no "new page" button; the trailing empty page disappears again when it is no longer needed.
     */
    public static int pageCount() {
        ensureLoaded();
        Page last = pages.get(pages.size() - 1);
        if (!last.isEmpty()) {
            pages.add(new Page());
        } else if (activePage == pages.size() - 2 && page().isEmpty()) {
            pages.remove(pages.size() - 1);
        }
        return pages.size();
    }

    private static boolean removeEmptyPages() {
        if (activePage != pages.size() - 1 && page().isEmpty()) {
            pages.remove(activePage);
            activePage = Math.min(activePage, pages.size() - 1);
            return true;
        }
        return false;
    }

    public static void nextPage() {
        ensureLoaded();
        if (removeEmptyPages()) return;
        activePage = activePage == pageCount() - 1 ? 0 : activePage + 1;
    }

    public static void prevPage() {
        pageCount();
        removeEmptyPages();
        activePage = activePage == 0 ? pageCount() - 1 : activePage - 1;
    }

    // ---------------------------------------------------------------- NEI "A" key and group helpers

    /** NEI's bookmark.add key: removes the item if it is already bookmarked, otherwise adds it. */
    public static void toggle(ItemStack stack, long amount) {
        if (stack.isEmpty()) return;
        Page page = page();
        Calculator.StackKey key = new Calculator.StackKey(stack);
        for (Entry e : page.entries) {
            if (e.isPlain() && e.key.equals(key)) {
                remove(page, e);
                return;
            }
        }
        add(stack, amount, NO_GROUP);
    }

    /** Is this item bookmarked as a plain item on the active page? */
    public static boolean isBookmarked(ItemStack stack) {
        if (stack.isEmpty()) return false;
        Calculator.StackKey key = new Calculator.StackKey(stack);
        for (Entry e : page().entries) {
            if (e.isPlain() && e.key.equals(key)) return true;
        }
        return false;
    }

    public static void removeGroup(Page page, int groupId) {
        page.entries.removeIf(e -> e.groupId == groupId);
        page.ghosts.removeIf(e -> e.groupId == groupId);
        page.metas.remove(groupId);
        page.normalize();
        save();
    }

    // ---------------------------------------------------------------- recipes

    public static boolean existsRecipe(Page page, String recipeId, int groupId) {
        for (Entry e : page.entries) if (e.sameRecipe(recipeId, groupId)) return true;
        return false;
    }

    /**
     * NEI's {@code BookmarkGrid.addRecipe}: appends the results, then the ingredients of the recipe to the group, each
     * item with {@code amount = factor * multiplier}. Does nothing (returns false) if the recipe is already there.
     */
    public static boolean addRecipe(Page page, RecipeData recipe, long multiplier, int groupId) {
        if (recipe.isEmpty() || existsRecipe(page, recipe.recipeId, groupId)) return false;
        int at = insertIndex(page, groupId);
        for (RecipeData.Part part : recipe.results) {
            page.entries.add(at++, recipeEntry(part, recipe.recipeId, ChainItem.Type.RESULT, multiplier, groupId));
        }
        for (RecipeData.Part part : recipe.ingredients) {
            page.entries.add(at++, recipeEntry(part, recipe.recipeId, ChainItem.Type.INGREDIENT, multiplier, groupId));
        }
        return true;
    }

    private static Entry recipeEntry(RecipeData.Part part, String recipeId, ChainItem.Type type, long multiplier,
        int groupId) {
        Entry e = new Entry(new Calculator.StackKey(part.stack), part.factor * multiplier, groupId);
        e.type = type;
        e.recipeId = recipeId;
        e.factor = part.factor;
        e.options = type == ChainItem.Type.INGREDIENT ? List.copyOf(part.options) : List.of();
        return e;
    }

    /**
     * NEI's {@code BookmarkPanel.addGroup}: saves recipes (a recipe tree, top recipe first) as one new group of the
     * active page, in todo-list view with the crafting chain on. Returns the group id, or 0 if nothing was added.
     */
    public static int addGroup(List<RecipeData> recipes, boolean todo, boolean chain) {
        Page page = page();
        int gid = page.nextGroupId();
        int before = page.entries.size();
        Set<String> unique = new HashSet<>();
        for (RecipeData r : recipes) {
            if (unique.add(r.recipeId)) addRecipe(page, r, 1, gid);
        }
        if (page.entries.size() == before) return NO_GROUP;
        GroupMeta m = page.meta(gid);
        m.todo = todo;
        m.chain = chain;
        page.normalize();
        save();
        return gid;
    }

    public static boolean removeRecipe(Page page, String recipeId, int groupId) {
        boolean removed = page.entries.removeIf(e -> e.sameRecipe(recipeId, groupId));
        removed |= page.ghosts.removeIf(e -> e.sameRecipe(recipeId, groupId));
        if (removed) {
            page.normalize();
            save();
        }
        return removed;
    }

    /**
     * Removes one bookmarked item. A recipe's only result removes the whole recipe, and so does {@code wholeRecipe}
     * (Shift) for any item of a recipe, like NEI's {@code BookmarkGrid.removeRecipe}.
     */
    public static void removeEntry(Page page, Entry entry, boolean wholeRecipe) {
        if (entry.isRecipe()) {
            if (!wholeRecipe && entry.type == ChainItem.Type.RESULT) {
                wholeRecipe = page.entries.stream()
                    .noneMatch(m -> m != entry && m.type == ChainItem.Type.RESULT && m.sameRecipe(entry.recipeId, entry.groupId));
            }
            if (wholeRecipe) {
                removeRecipe(page, entry.recipeId, entry.groupId);
                return;
            }
        }
        remove(page, entry);
    }

    /**
     * NEI's {@code BookmarkPanel.removeSlot} for a recipe item in a crafting-chain group: the recipes a collapsed
     * recipe swallows go together. Returns the recipes to remove, or empty when the item is not in a collapsed recipe.
     */
    public static Set<String> collapsedFamily(Page page, int groupId, String recipeId) {
        ChainDetails d = chain(page, groupId);
        if (d == null || recipeId == null) return Set.of();
        Set<String> recipes = d.recipeRelations.getOrDefault(recipeId, Set.of());
        if (recipes.isEmpty()) {
            for (Map.Entry<String, Set<String>> r : d.recipeRelations.entrySet()) {
                if (r.getValue().contains(recipeId)) {
                    recipes = r.getValue();
                    break;
                }
            }
        }
        return recipes;
    }

    /**
     * NEI's {@code BookmarkGrid.toggleCollapsedRecipe}: Alt + click on a recipe item of a crafting chain. A recipe that
     * a collapsed recipe swallows toggles that collapsed recipe. A recipe in the middle of the chain runs 0 times when
     * collapsed (its parent asks for it) and once again when expanded.
     */
    public static void toggleCollapsedRecipe(Page page, int groupId, String recipeId) {
        ChainDetails d = chain(page, groupId);
        if (d == null) return;
        String target = recipeId;
        for (Map.Entry<String, Set<String>> r : d.recipeRelations.entrySet()) {
            if (r.getValue().contains(recipeId)) {
                target = r.getKey();
                break;
            }
        }
        GroupMeta m = page.meta(groupId);
        boolean nowCollapsed = m.collapsedRecipes.add(target);
        if (!nowCollapsed) m.collapsedRecipes.remove(target);
        if (d.recipeInMiddle.contains(target)) {
            for (Entry e : page.entries) {
                if (e.sameRecipe(target, groupId)) {
                    if (nowCollapsed && e.multiplier() == 1) e.amount = 0;
                    else if (!nowCollapsed && e.multiplier() == 0) e.amount = e.factor;
                }
            }
        }
        save();
    }

    /**
     * NEI's {@code BookmarkPanel.removeSlot}: the Remove key over a slot. {@code slotRecipe} is the recipe the slot
     * stands for (null for a plain item). In a collapsed crafting chain only Shift removes (the whole group); a slot of
     * a collapsed recipe removes the recipes it swallows with it, and only with Shift.
     */
    public static void removeSlot(Page page, Entry entry, String slotRecipe, boolean removeFull) {
        GroupMeta m = page.metas.get(entry.groupId);
        if (m != null && m.chain && m.collapsed && page.hasRecipes(entry.groupId)) {
            if (removeFull) removeGroup(page, entry.groupId);
            return;
        }
        if (slotRecipe != null) {
            Set<String> family = collapsedFamily(page, entry.groupId, slotRecipe);
            if (!family.isEmpty()) {
                if (!removeFull) return;
                for (String r : new ArrayList<>(family)) removeRecipe(page, r, entry.groupId);
                return;
            }
        }
        removeEntry(page, entry, removeFull);
    }

    /** How often the recipe is set to run: the largest multiplier of its results. */
    public static long recipeMultiplier(Page page, String recipeId, int groupId) {
        long best = 0;
        for (Entry e : page.entries) {
            if (e.sameRecipe(recipeId, groupId) && e.type == ChainItem.Type.RESULT) best = Math.max(best, e.multiplier());
        }
        return best;
    }

    public static void setRecipeMultiplier(Page page, String recipeId, int groupId, long multiplier) {
        for (Entry e : page.entries) {
            if (e.sameRecipe(recipeId, groupId)) e.amount = e.factor * multiplier;
        }
    }

    // ---------------------------------------------------------------- crafting chain

    private static ChainDetails computeChain(Page page, int groupId) {
        List<ChainItem> items = new ArrayList<>();
        boolean recipes = false;
        for (Entry e : page.entries) {
            if (e.groupId == groupId) {
                items.add(e.toChain());
                recipes |= e.isRecipe();
            }
        }
        GroupMeta meta = page.metas.get(groupId);
        return recipes ? ChainDetails.of(items, meta != null ? meta.collapsedRecipes : Set.of()) : null;
    }

    // ---------------------------------------------------------------- sharing (Ctrl+L chat links)

    /**
     * What a chat link carries (NEI's BookmarkPayload), in the bookmark file's own line format:
     * {@code share<TAB>kind<TAB>collapsed<TAB>chain<TAB>todo}, an optional {@code target<TAB>itemId} (the item the chat
     * link shows), then one {@code item} line per entry. Kind is {@code item}, {@code recipe} or {@code group}.
     */
    private static String sharePayload(String kind, GroupMeta meta, String targetId, List<Entry> entries) {
        List<String> lines = new ArrayList<>();
        lines.add(
            "share\t" + kind + "\t" + (meta != null && meta.collapsed) + "\t" + (meta != null && meta.chain) + "\t"
                + (meta != null && meta.todo) + "\t" + (meta != null ? joinRecipes(meta.collapsedRecipes) : ""));
        if (targetId != null) lines.add("target\t" + clean(targetId));
        for (Entry e : entries) lines.add(line(e, NO_GROUP));
        return String.join("\n", lines);
    }

    /** NEI BookmarkPayload.of(groupId): every item of the group, with how the group is shown. */
    public static String shareGroup(Page page, int groupId) {
        List<Entry> entries = new ArrayList<>();
        for (Entry e : page.entries) if (e.groupId == groupId) entries.add(e);
        for (Entry e : page.ghosts) if (e.groupId == groupId) entries.add(e);
        return entries.isEmpty() ? null : sharePayload("group", page.metas.get(groupId), null, entries);
    }

    /** NEI BookmarkPayload.of(BookmarkItem) for a recipe item: every item of that recipe in its group. */
    public static String shareRecipe(Page page, Entry entry) {
        if (!entry.isRecipe()) return null;
        List<Entry> entries = new ArrayList<>();
        for (Entry e : page.entries) if (e.sameRecipe(entry.recipeId, entry.groupId)) entries.add(e);
        return sharePayload("recipe", null, entry.itemId(), entries);
    }

    /** NEI BookmarkPayload.of(Recipe): a recipe that is not bookmarked (hovered in the recipe screen, or a favorite). */
    public static String shareRecipe(RecipeData recipe, String targetId) {
        Page scratch = new Page();
        if (!addRecipe(scratch, recipe, 1, NO_GROUP)) return null;
        return sharePayload("recipe", null, targetId, scratch.entries);
    }

    /** NEI BookmarkPayload.of(stack, recipeId) without the recipe: one plain item. */
    public static String shareItem(ItemStack stack, long amount) {
        if (stack.isEmpty()) return null;
        Entry e = new Entry(new Calculator.StackKey(stack), Math.max(1, amount), NO_GROUP);
        return sharePayload("item", null, RecipeData.idOf(stack), List.of(e));
    }

    /**
     * NEI's /nei_bookmark: adds what a chat link carries to the active page. A group becomes a new group (shown the
     * way the sender had it), a recipe goes to the default group unless it is already there, an item is added to the
     * default group (amount added to an existing plain item). Returns false for a payload it cannot read.
     */
    public static boolean importShared(String payload) {
        String[] lines = payload.split("\n");
        String[] head = lines[0].split("\t", -1);
        if (head.length < 5 || !head[0].equals("share")) return false;
        String kind = head[1];
        if (!kind.equals("item") && !kind.equals("recipe") && !kind.equals("group")) return false;
        Page page = page();
        int gid = NO_GROUP;
        if (kind.equals("group")) {
            gid = page.nextGroupId();
            GroupMeta m = page.meta(gid);
            m.collapsed = Boolean.parseBoolean(head[2]);
            m.chain = Boolean.parseBoolean(head[3]);
            m.todo = Boolean.parseBoolean(head[4]);
            if (head.length >= 6) readRecipes(m, head[5]);
        }
        boolean added = false;
        Set<String> alreadyThere = new HashSet<>();
        if (kind.equals("recipe")) {
            for (int i = 1; i < lines.length; i++) {
                String[] p = lines[i].split("\t", -1);
                if (p.length >= 7 && !p[5].equals("-") && existsRecipe(page, p[5], gid)) alreadyThere.add(p[5]);
            }
        }
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split("\t", -1);
            if (p.length < 4 || !p[0].equals("item")) continue;
            boolean recipeItem = p.length >= 7 && !p[5].isEmpty() && !p[5].equals("-");
            if (recipeItem && alreadyThere.contains(p[5])) continue;
            if (!recipeItem && !kind.equals("group")) {
                ItemStack parsed = FluidStacks.stackOf(p[1]);
                if (!parsed.isEmpty()) {
                    add(parsed, Math.max(1, parseLong(p[2], 1)), NO_GROUP);
                    added = true;
                    continue;
                }
            }
            readItem(page, p, gid);
            added = true;
        }
        if (added) {
            page.normalize();
            save();
        }
        // a valid link whose recipe is already bookmarked still counts as handled
        return true;
    }

    /** Whether the wheel can switch this entry's item: an ingredient of a bookmarked recipe that accepts several items. */
    public static boolean hasOptions(Entry entry) {
        return entry.key != null && entry.isRecipe() && entry.type == ChainItem.Type.INGREDIENT && entry.options.size() > 1;
    }

    /**
     * NEI's Shift + wheel over an ingredient with several permutations (tag slot): the next accepted item
     * ({@code items.get((size - shift + index) % size)}, wheel up = the one before). The recipe is the same, the item
     * and so the chain maths change.
     */
    public static boolean cycleOption(Entry entry, int wheel) {
        if (!hasOptions(entry)) return false;
        int size = entry.options.size();
        int at = Math.max(0, entry.options.indexOf(entry.itemId()));
        String id = entry.options.get((((at - wheel) % size) + size) % size);
        ItemStack chosen = FluidStacks.stackOf(id);
        if (chosen.isEmpty()) return false;
        entry.key = new Calculator.StackKey(chosen);
        save();
        return true;
    }

    /** Fresh chain maths of a crafting-chain group for autocrafting (which changes it), or null. */
    public static ChainMath autocraftChain(Page page, int groupId) {
        if (chain(page, groupId) == null) return null;
        List<ChainItem> items = new ArrayList<>();
        for (Entry e : page.entries) {
            if (e.groupId == groupId) items.add(e.toChain());
        }
        return ChainMath.of(items, page.meta(groupId).collapsedRecipes);
    }

    /**
     * NEI's createRecipeChainMath(groupId) as pulling items uses it: a crafting-chain group keeps its recipes, any other
     * group is just its items, each at the amount it shows. Null when the group is empty.
     */
    public static ChainMath pullMath(Page page, int groupId) {
        GroupMeta m = page.metas.get(groupId);
        boolean chain = m != null && m.chain;
        List<ChainItem> items = new ArrayList<>();
        for (Entry e : page.entries) {
            if (e.groupId != groupId) continue;
            ChainItem c = e.toChain();
            items.add(chain ? c : new ChainItem(e.itemId(), null, ChainItem.Type.ITEM, 1, c.amount(), null, e));
        }
        return items.isEmpty() ? null : ChainMath.of(items, chain ? m.collapsedRecipes : Set.of());
    }

    /** NEI's pullRecipeItems: a single item and the amount wanted. */
    public static ChainMath pullMath(String itemId, long amount) {
        return ChainMath.of(List.of(new ChainItem(itemId, null, ChainItem.Type.ITEM, 1, Math.max(1, amount), null, null)));
    }

    /**
     * NEI's Shift+C over a recipe's result in the panel: just that recipe, run as often as the chain calculates for it
     * (as often as it is set to outside a chain, at least once). Null for items that are not a recipe's result.
     */
    public static ChainMath autocraftRecipe(Page page, Entry entry) {
        if (!entry.isRecipe() || entry.type == ChainItem.Type.INGREDIENT) return null;
        // NEI createRecipeChainMath(groupId, recipeId): a collapsed recipe takes the recipes it swallows along, and in
        // a collapsed group the whole group is the chain
        GroupMeta m = page.metas.get(entry.groupId);
        boolean chain = m != null && m.chain;
        boolean wholeGroup = m != null && m.collapsed;
        String top = entry.recipeId;
        Set<String> family = Set.of(top);
        ChainDetails d = chain && !wholeGroup ? chain(page, entry.groupId) : null;
        if (d != null) {
            for (Map.Entry<String, Set<String>> relation : d.recipeRelations.entrySet()) {
                if (relation.getValue().contains(top)) {
                    top = relation.getKey();
                    break;
                }
            }
            family = d.recipeRelations.getOrDefault(top, Set.of(top));
        }
        List<ChainItem> items = new ArrayList<>();
        for (Entry e : page.entries) {
            if (e.groupId != entry.groupId) continue;
            if (!wholeGroup && (e.recipeId == null || !family.contains(e.recipeId))) continue;
            ChainDetails.Calc c = wholeGroup ? null : calc(page, e);
            if (!wholeGroup && top.equals(e.recipeId)) {
                items.add(e.toChain().copyWithMultiplier(c != null ? c.calculatedMultiplier() : Math.max(1, e.multiplier())));
            } else {
                items.add(e.toChain());
            }
        }
        return ChainMath.of(items, wholeGroup && chain ? m.collapsedRecipes : Set.of());
    }

    /** Autocrafting a recipe that is not bookmarked (a favorite recipe over JEI's list): one run of it. */
    public static ChainMath autocraftRecipe(RecipeData recipe) {
        Page scratch = new Page();
        if (!addRecipe(scratch, recipe, 1, NO_GROUP)) return null;
        List<ChainItem> items = new ArrayList<>();
        for (Entry e : scratch.entries) items.add(e.toChain());
        return ChainMath.of(items);
    }

    /** The chain maths of the group, or null when its crafting chain is off or it has no recipes. Cached until the next change. */
    public static ChainDetails chain(Page page, int groupId) {
        GroupMeta m = page.metas.get(groupId);
        if (m == null || !m.chain) return null;
        Map<Integer, ChainDetails> perPage = chainCache.computeIfAbsent(page, k -> new HashMap<>());
        if (!perPage.containsKey(groupId)) perPage.put(groupId, computeChain(page, groupId));
        return perPage.get(groupId);
    }

    /**
     * NEI's {@code createChainItems}: the items of a crafting-chain group as the chain maths takes them (each one
     * carries its {@link Entry} as payload). Empty when the group's crafting chain is off or has no recipes.
     */
    public static List<ChainItem> chainItems(Page page, int groupId) {
        List<ChainItem> items = new ArrayList<>();
        if (chain(page, groupId) == null) return items;
        for (Entry e : page.entries) {
            if (e.groupId == groupId) items.add(e.toChain());
        }
        return items;
    }

    /** Is the group still there (the page can be removed, or the group emptied, while a recipe screen is open)? */
    public static boolean groupExists(Page page, int groupId) {
        return pages.contains(page) && page.entries.stream()
            .anyMatch(e -> e.groupId == groupId);
    }

    public static ChainDetails.Calc calc(Page page, Entry entry) {
        ChainDetails d = chain(page, entry.groupId);
        return d == null ? null : d.calculated.get(entry);
    }

    /** The amount to draw: the calculated one in a crafting-chain group, else the stored one. */
    public static long displayAmount(Page page, Entry entry) {
        ChainDetails.Calc c = calc(page, entry);
        return c != null ? c.calculatedAmount() : entry.amount;
    }

    // ---------------------------------------------------------------- amounts

    /** NEI's shiftMultiplier: {@code multiplier + shift} rounded to a multiple of |shift|, never below the minimum. */
    static long shiftMultiplier(long multiplier, long shift, long minMultiplier) {
        long current = ((multiplier + shift) / shift) * shift;
        return Math.min(Integer.MAX_VALUE, current <= 0 && multiplier > 1 ? 1 : Math.max(minMultiplier, current));
    }

    /** NEI's shiftRecipeAmount: Ctrl + wheel on a recipe item changes how often the whole recipe runs. */
    public static void shiftRecipeAmount(Page page, int groupId, String recipeId, long shift) {
        ChainDetails d = chain(page, groupId);
        boolean middle = d != null && d.recipeInMiddle.contains(recipeId);
        long multiplier = Integer.MAX_VALUE;
        for (Entry e : page.entries) {
            if (e.sameRecipe(recipeId, groupId)) {
                multiplier = Math.min(
                    shiftMultiplier(Math.max(middle ? 1 : 0, e.multiplier()), shift, middle ? 1 : 0),
                    multiplier);
            }
        }
        setRecipeMultiplier(page, recipeId, groupId, multiplier);
        save();
    }

    /** NEI's shiftItemAmount for a plain item. */
    public static void shiftItemAmount(Page page, Entry entry, long shift) {
        entry.amount = shiftMultiplier(entry.amount, shift, 1);
        save();
    }

    /**
     * NEI's shiftGroupAmount. A plain group scales all amounts together, keeping their ratio; a crafting-chain group
     * with recipes changes how often its output recipes run.
     */
    public static void shiftGroupAmount(Page page, int groupId, long shift) {
        ChainDetails d = page.hasRecipes(groupId) ? chain(page, groupId) : null;
        if (d != null) {
            Map<String, Long> multipliers = new LinkedHashMap<>();
            for (Entry e : page.entries) {
                if (e.isRecipe() && e.groupId == groupId && d.outputRecipes.contains(e.recipeId)) {
                    boolean middle = d.recipeInMiddle.contains(e.recipeId);
                    long m = shiftMultiplier(Math.max(middle ? 1 : 0, e.multiplier()), shift, middle ? 1 : 0);
                    multipliers.merge(e.recipeId, m, Math::min);
                }
            }
            for (Entry e : page.entries) {
                if (e.isRecipe() && e.groupId == groupId && multipliers.containsKey(e.recipeId)) {
                    e.amount = e.factor * multipliers.get(e.recipeId);
                }
            }
            save();
            return;
        }
        List<Entry> items = new ArrayList<>();
        long gcd = 0;
        for (Entry e : page.entries) {
            if (e.groupId == groupId) {
                items.add(e);
                gcd = gcd(gcd, e.amount);
            }
        }
        if (items.isEmpty() || gcd <= 0) return;
        long factor = Math.max(1, gcd + shift);
        for (Entry e : items) {
            e.amount = Math.max(1, e.amount / gcd * factor);
        }
        save();
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
