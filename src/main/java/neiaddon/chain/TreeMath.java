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
import java.util.List;
import java.util.Map;
import java.util.Set;

/** NEI's CraftingTreeMath: the chain maths, recording every step as a node of a tree. */
public class TreeMath extends ChainMath {

    public final List<TreeSlot> rootSlots = new ArrayList<>();
    public final List<TreeSlot> items = new ArrayList<>();
    public final Map<TreeSlot, TreeSlot> parentOf = new HashMap<>();
    public final Map<TreeSlot, List<TreeSlot>> childrenOf = new HashMap<>();

    private final List<TreeSlot> parentStack = new ArrayList<>();
    private TreeSlot lastItem = null;

    public TreeMath(List<ChainItem> chainItems, Set<String> collapsedRecipes) {
        super(chainItems, collapsedRecipes);
    }

    private TreeSlot peek() {
        return parentStack.isEmpty() ? null : parentStack.get(parentStack.size() - 1);
    }

    @Override
    public ChainMath refresh() {
        this.rootSlots.clear();
        this.items.clear();
        this.parentOf.clear();
        this.childrenOf.clear();
        this.parentStack.clear();
        this.lastItem = null;
        return super.refresh();
    }

    @Override
    protected void calculationStepTrigger(ChainItem ingrItem, ChainItem prefItem, long requestedAmount,
        long containerAmount, long inventoryAmount, long ingrAmount, long produced) {
        if (ingrItem.type == ChainItem.Type.RESULT) {
            final long required = this.requiredAmount.get(prefItem);
            final TreeSlot slot = new TreeSlot(ingrItem, Math.max(0, required - requestedAmount), requestedAmount, required, 0, prefItem);
            this.rootSlots.add(slot);
            this.items.add(slot);
            this.lastItem = slot;
        } else {
            final TreeSlot slot = new TreeSlot(ingrItem, 0, requestedAmount, produced, containerAmount, prefItem);
            this.items.add(slot);
            this.parentOf.put(slot, peek());
            this.childrenOf.computeIfAbsent(peek(), k -> new ArrayList<>())
                .add(slot);
            this.lastItem = slot;
        }
    }

    @Override
    protected void prepareIngredients(String recipeId, long multiplier, List<String> visited) {
        this.parentStack.add(this.lastItem);
        super.prepareIngredients(recipeId, multiplier, visited);
        this.lastItem = this.parentStack.remove(this.parentStack.size() - 1);
    }

    @Override
    protected void calculateSuitableRecipe(ChainItem ingrItem, long ingrMultiplier, List<String> visited) {
        final ChainItem prefItem = this.preferredItems.get(ingrItem);
        super.calculateSuitableRecipe(ingrItem, ingrMultiplier, visited);

        // NEI: an item that needs nothing made still shows the recipe that would make it
        if (prefItem != null && !isForeignBoxBoundary(ingrItem) && !visited.contains(prefItem.recipeId)
            && !this.childrenOf.containsKey(this.lastItem)) {
            visited.add(prefItem.recipeId);
            prepareIngredients(prefItem.recipeId, visited);
            visited.remove(prefItem.recipeId);
        }
    }

    protected void prepareIngredients(String recipeId, List<String> visited) {
        this.parentStack.add(this.lastItem);

        for (ChainItem item : this.ingredientsByRecipe.getOrDefault(recipeId, Collections.emptyList())) {
            if (!item.emptyFactor()) {
                calculateSuitableRecipe(item, visited);
            }
        }

        this.lastItem = this.parentStack.remove(this.parentStack.size() - 1);
    }

    protected void calculateSuitableRecipe(ChainItem ingrItem, List<String> visited) {
        final ChainItem prefItem = this.preferredItems.get(ingrItem);
        final TreeSlot slot = new TreeSlot(ingrItem, 0, 0, 0, 0, prefItem);

        this.items.add(slot);
        this.parentOf.put(slot, peek());
        this.childrenOf.computeIfAbsent(peek(), k -> new ArrayList<>())
            .add(slot);
        this.lastItem = slot;

        if (prefItem != null && !isForeignBoxBoundary(ingrItem) && !visited.contains(prefItem.recipeId)) {
            visited.add(prefItem.recipeId);
            prepareIngredients(prefItem.recipeId, visited);
            visited.remove(prefItem.recipeId);
        }
    }
}
