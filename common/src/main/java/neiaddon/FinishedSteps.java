package neiaddon;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import neiaddon.TreeState.CollapseMode;
import neiaddon.chain.ChainDetails;
import neiaddon.chain.ChainItem;
import neiaddon.chain.TreeGraph;
import neiaddon.chain.TreeMath;
import neiaddon.chain.TreeSlot;

/**
 * The steps of a crafting-chain group that the crafting tree folds away under finished items (display only).
 *
 * <p>With the tree's collapse button on "Collapse items with nothing left to craft" ({@link CollapseMode#SMART}), the tree
 * folds every non-root node none of whose children still need crafting, so the sub-tree under a finished item is hidden.
 * This class builds the same tree headless (the group's chain items, the open menu's inventory as the tree does it, then
 * {@link TreeGraph#rebuild}) and returns the recipes whose every node is finished: a node is finished when neither it nor
 * anything below it still needs crafting, so the panel drops the finished step itself, not only what is under it (the tree
 * screen keeps finished items as folded nodes; the panel going to the roots alone is what players expect of a todo group
 * whose whole chain sits in the inventory). {@link BookmarkPanel} skips those recipes' entries when it lays the group
 * out. Nothing is removed from the group, the amounts, the tooltips or the tree screen are unchanged, and the hidden
 * recipes come back as soon as the rule no longer hides them.
 *
 * <p>The rule applies only when the group is a crafting chain, the collapse mode is SMART, and the group counts the
 * inventory (its crafting mode, or the inventory snapshot setting), as the tree does. Roots are never hidden, so a group
 * never becomes empty. The result is cached per page and group: rebuilt when the group's chain maths, its collapsed
 * recipes, the collapse mode, the snapshot setting or the crafting mode change, and at most every {@link #REFRESH_TICKS}
 * client ticks (the inventory is read again then).
 */
final class FinishedSteps {

    /** Client ticks between two builds of one group's hidden set (the tree's LIVE_TICKS). */
    private static final int REFRESH_TICKS = 20;

    private static boolean warned;

    /** The last result of one group, with the inputs it was built from. */
    private static final class Cached {
        final ChainDetails chain;
        final Set<String> collapsed;
        final CollapseMode mode;
        final boolean snapshot;
        final boolean crafting;
        final int tick;
        final Set<String> hidden;

        Cached(ChainDetails chain, Set<String> collapsed, CollapseMode mode, boolean snapshot, boolean crafting, int tick,
            Set<String> hidden) {
            this.chain = chain;
            this.collapsed = collapsed;
            this.mode = mode;
            this.snapshot = snapshot;
            this.crafting = crafting;
            this.tick = tick;
            this.hidden = hidden;
        }
    }

    private static final Map<Bookmarks.Page, Map<Integer, Cached>> CACHE = new WeakHashMap<>();

    private FinishedSteps() {}

    /**
     * The recipe ids of group {@code gid} that the SMART collapse of the crafting tree hides: a recipe is hidden when it
     * appears in the tree and every node with that recipe is finished (nothing in or below it needs crafting). Empty when
     * the group is not a crafting chain, does not count the inventory, or the collapse mode is not SMART. Never null.
     */
    static Set<String> hiddenRecipes(Bookmarks.Page page, int gid) {
        Bookmarks.GroupMeta meta = page.metas.get(gid);
        if (meta == null || !meta.chain) return Set.of();
        boolean snapshot = TreeState.useInventorySnapshot();
        boolean crafting = meta.crafting;
        CollapseMode mode = TreeState.collapseMode();
        if (mode != CollapseMode.SMART || !(snapshot || crafting)) return Set.of();

        ChainDetails chain = Bookmarks.chain(page, gid);
        Set<String> collapsed = meta.collapsedRecipes;
        int now = McCompat.guiTicks();
        Map<Integer, Cached> perPage = CACHE.computeIfAbsent(page, k -> new HashMap<>());
        Cached c = perPage.get(gid);
        if (c != null && c.chain == chain && c.mode == mode && c.snapshot == snapshot && c.crafting == crafting
            && c.collapsed.equals(collapsed) && now - c.tick >= 0 && now - c.tick < REFRESH_TICKS) {
            return c.hidden;
        }

        Set<String> hidden;
        try {
            hidden = build(page, gid, meta, snapshot || crafting);
        } catch (RuntimeException | LinkageError e) {
            warnOnce(e);
            hidden = Set.of();
        }
        perPage.put(gid, new Cached(chain, Set.copyOf(collapsed), mode, snapshot, crafting, now, hidden));
        return hidden;
    }

    /** The tree the tree screen builds for this group (CraftingTreeScreen.rebuildTree), then the finished nodes of that tree. */
    private static Set<String> build(Bookmarks.Page page, int gid, Bookmarks.GroupMeta meta, boolean counted) {
        TreeMath math = new TreeMath(Bookmarks.chainItems(page, gid), meta.collapsedRecipes);
        math.initialItems.clear();
        Map<String, Long> inventory = counted ? CraftingTreeScreen.readInventory() : Map.of();
        for (Map.Entry<String, Long> e : inventory.entrySet()) {
            math.initialItems.add(new ChainItem(e.getKey(), null, ChainItem.Type.ITEM, 1, e.getValue(), null, null, -1));
        }
        TreeGraph graph = new TreeGraph();
        graph.rebuild(math);

        // a non-root node is finished when neither it nor anything below it still needs crafting (leftAmount 0 all the
        // way down), so its recipe is not needed any more and its entries can go. Unlike the tree screen, which keeps a
        // finished item on screen as a folded node, the panel drops the finished step itself: when the inventory can
        // craft everything, a group collapses to its roots (its first row) alone.
        Set<TreeSlot> hiddenNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<TreeSlot, Boolean> needed = new IdentityHashMap<>();
        Set<TreeSlot> rootNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        rootNodes.addAll(graph.roots); // a list: a set keeps the loop linear for a long GregTech chain
        for (TreeSlot node : graph.items) {
            if (!rootNodes.contains(node) && !needsCrafting(graph, node, needed)) {
                hiddenNodes.add(node);
            }
        }

        // a recipe is hidden when all of its nodes are
        Map<String, int[]> counts = new HashMap<>();
        for (TreeSlot node : graph.items) {
            String recipe = node.recipeId();
            if (recipe == null) continue;
            int[] n = counts.computeIfAbsent(recipe, k -> new int[2]);
            n[0]++;
            if (hiddenNodes.contains(node)) n[1]++;
        }
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            if (e.getValue()[0] > 0 && e.getValue()[0] == e.getValue()[1]) result.add(e.getKey());
        }
        return Collections.unmodifiableSet(result);
    }

    /** Whether {@code node} or anything below it still needs crafting: its own leftAmount, or a descendant's. */
    private static boolean needsCrafting(TreeGraph graph, TreeSlot node, Map<TreeSlot, Boolean> memo) {
        Boolean known = memo.get(node);
        if (known != null) return known;
        memo.put(node, Boolean.FALSE); // a slot on the current path: counted as not needed, the tree has no cycles anyway
        boolean needed = node.leftAmount != 0;
        for (TreeSlot child : graph.childrenOf.getOrDefault(node, List.of())) {
            needed |= needsCrafting(graph, child, memo);
        }
        memo.put(node, needed);
        return needed;
    }

    private static void warnOnce(Throwable e) {
        if (warned) return;
        warned = true;
        NeiAddon.LOGGER.warn("Could not work out the finished steps of a crafting-chain group; the bookmark panel shows all of them", e);
    }
}
