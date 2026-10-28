/*
 * Crafting tree maths for the NEI addon for JEI.
 *
 * Port of GTNH NotEnoughItems' codechicken.nei.bookmark.tree (https://github.com/GTNewHorizons/NotEnoughItems),
 * originally by Chicken-Bones, GTNewHorizons and the NotEnoughItems contributors. Licensed under the GNU Lesser
 * General Public License, version 3 or (at your option) any later version.
 *
 * Uses no Minecraft classes so it can be tested with a plain javac (see tools/TreeCheck.java).
 */
package neiaddon.chain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** NEI's CraftingTreeGraph: the tree of a {@link TreeMath} run plus what the drawing needs to know about it. */
public final class TreeGraph {

    public final List<TreeSlot> roots = new ArrayList<>();
    public final List<TreeSlot> items = new ArrayList<>();
    public final Map<TreeSlot, TreeSlot> parentOf = new HashMap<>();
    public final Map<TreeSlot, List<TreeSlot>> childrenOf = new HashMap<>();

    /** Nodes with something still missing somewhere below them. */
    public final Set<TreeSlot> subtreeMissingCache = new HashSet<>();
    /** Nodes with a child that still has to be crafted. */
    public final Set<TreeSlot> childrenNeedCraftingCache = new HashSet<>();

    public void rebuild(TreeMath math) {
        this.subtreeMissingCache.clear();
        this.childrenNeedCraftingCache.clear();

        math.refresh();

        this.roots.clear();
        this.roots.addAll(math.rootSlots);
        this.items.clear();
        this.items.addAll(math.items);
        this.parentOf.clear();
        this.parentOf.putAll(math.parentOf);
        this.childrenOf.clear();
        this.childrenOf.putAll(math.childrenOf);

        for (TreeSlot root : this.roots) {
            computeSubtreeMissing(root);
        }

        for (Map.Entry<TreeSlot, List<TreeSlot>> entry : this.childrenOf.entrySet()) {
            if (entry.getValue()
                .stream()
                .anyMatch(child -> child.leftAmount != 0)) {
                this.childrenNeedCraftingCache.add(entry.getKey());
            }
        }
    }

    /** The recipes that are only there to make {@code node}'s item: removed with it. */
    public Set<String> collectUnusedRecipes(TreeSlot node) {
        final Map<String, Set<String>> children = new HashMap<>();
        final Map<String, Set<String>> consumers = new HashMap<>();
        int rootCount = 0;

        for (TreeSlot item : this.items) {
            if (item.prefItem != null) {
                children.computeIfAbsent(item.ingrItem.recipeId, k -> new HashSet<>())
                    .add(item.prefItem.recipeId);
                consumers.computeIfAbsent(item.prefItem.recipeId, k -> new HashSet<>())
                    .add(item.ingrItem.recipeId);

                if (item.ingrItem.recipeId.equals(node.prefItem.recipeId)) {
                    rootCount++;
                }
            }
        }

        final Set<String> unusedRecipes = new HashSet<>();
        final String rootRecipeId = node.prefItem.recipeId;

        if (rootCount <= 1) {
            unusedRecipes.add(rootRecipeId);
            collectSubtree(rootRecipeId, children, consumers, unusedRecipes);
        }

        return unusedRecipes;
    }

    private void collectSubtree(String recipeId, Map<String, Set<String>> children, Map<String, Set<String>> consumers,
        Set<String> unusedRecipes) {
        for (String child : children.getOrDefault(recipeId, Collections.emptySet())) {
            if (consumers.get(child)
                .size() == 1 && unusedRecipes.add(child)) {
                collectSubtree(child, children, consumers, unusedRecipes);
            }
        }
    }

    private boolean computeSubtreeMissing(TreeSlot node) {
        boolean hasMissing = !this.childrenOf.containsKey(node);

        for (TreeSlot child : this.childrenOf.getOrDefault(node, Collections.emptyList())) {
            hasMissing |= child.leftAmount != 0 && computeSubtreeMissing(child);
        }

        if (hasMissing) {
            this.subtreeMissingCache.add(node);
        }

        return hasMissing;
    }
}
