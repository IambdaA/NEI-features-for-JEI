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

    /**
     * Issue #42: a recipe with several results is one node, not one per wanted result. Roots go in by their recipe;
     * children go in under their parent by what they stand for, so the maths can merge into a node it already made.
     */
    private final Map<String, TreeSlot> rootByRecipe = new HashMap<>();
    private final Map<TreeSlot, Map<Object, TreeSlot>> childrenByKey = new HashMap<>();

    /**
     * Issue #42 follow-up: per recipe, the result the player entered the recipe on — the one whose favorite points at
     * this recipe (the shift+F pick or the heart-pick set it; the tree screen looks it up, this package cannot). The
     * recipe's node then shows that result at its leftmost slot, the others keep their order. Never set (the plain checks,
     * the SMART-collapse maths) keeps the recipe's own order.
     */
    private Map<String, String> chosenOutputs = Map.of();

    public void setChosenOutputs(Map<String, String> chosenItemByRecipe) {
        this.chosenOutputs = chosenItemByRecipe == null ? Map.of() : chosenItemByRecipe;
    }

    public TreeMath(List<ChainItem> chainItems, Set<String> collapsedRecipes) {
        super(chainItems, collapsedRecipes);
    }

    private TreeSlot peek() {
        return parentStack.isEmpty() ? null : parentStack.get(parentStack.size() - 1);
    }

    /** What a child node stands for, for the merge above. */
    private Object mergeKey(ChainItem ingrItem, ChainItem prefItem) {
        if (prefItem == null || isForeignBoxBoundary(ingrItem)) {
            return ingrItem;
        }
        return prefItem.recipeId;
    }

    /** Every other result of the node's recipe, shown too (issue #42: even one nothing downstream needs). */
    private void addMissingResults(TreeSlot slot, ChainItem prefItem) {
        if (prefItem == null) {
            return;
        }
        for (ChainItem result : this.resultsByRecipe.getOrDefault(prefItem.recipeId, Collections.emptyList())) {
            if (!result.emptyFactor() && !slot.hasOutput(result)) {
                slot.addOutput(result, result, 0, 0, 0, 0, false);
            }
        }
    }

    @Override
    public ChainMath refresh() {
        this.rootSlots.clear();
        this.items.clear();
        this.parentOf.clear();
        this.childrenOf.clear();
        this.rootByRecipe.clear();
        this.childrenByKey.clear();
        this.parentStack.clear();
        this.lastItem = null;
        return super.refresh();
    }

    @Override
    protected void calculationStepTrigger(ChainItem ingrItem, ChainItem prefItem, long requestedAmount,
        long containerAmount, long inventoryAmount, long ingrAmount, long produced) {
        if (ingrItem.type == ChainItem.Type.RESULT) {
            final long required = this.requiredAmount.get(prefItem);
            final TreeSlot known = this.rootByRecipe.get(prefItem.recipeId);
            if (known != null) {
                known.addOutput(ingrItem, prefItem, Math.max(0, required - requestedAmount), requestedAmount, required,
                    0, false);
                this.lastItem = known;
                return;
            }
            final TreeSlot slot = new TreeSlot(ingrItem, Math.max(0, required - requestedAmount), requestedAmount, required, 0, prefItem);
            addMissingResults(slot, prefItem);
            // issue #42 follow-up: the entered recipe shows the result it was entered on leftmost (a recipe pulled
            // into the tree by a parent already leads with the result that parent needs, so only roots need this)
            final String chosen = this.chosenOutputs.get(prefItem.recipeId);
            if (chosen != null) {
                slot.moveOutputFirst(chosen);
            }
            this.rootByRecipe.put(prefItem.recipeId, slot);
            this.rootSlots.add(slot);
            this.items.add(slot);
            this.lastItem = slot;
        } else {
            final ContainerOps ops = containerOps;
            boolean isContainerItem = false;
            final Object stack = ops != null ? ops.stackOf(prefItem != null ? prefItem : ingrItem) : null;

            if (stack != null) {
                // NEI's ItemTreeSlot.isContainerItem: the item comes back as itself (a tool)
                if (ops.hasContainerItem(stack)) {
                    final Object back = ops.remainder(stack);
                    isContainerItem = back != null && ops.sameItem(stack, back);
                }

                if (prefItem != null && hasContainerItem(stack)) {
                    if (requestedAmount > 1) {
                        final long requestedSteps = getToolsContainerItems(ops.copy(stack), requestedAmount).leftSteps;
                        requestedAmount = (long) Math
                            .ceil((double) requestedAmount / Math.max(1, requestedAmount - requestedSteps));
                    }

                    if (containerAmount > 1) {
                        final long containerSteps = getToolsContainerItems(ops.copy(stack), containerAmount).leftSteps;
                        containerAmount = (long) Math
                            .ceil((double) containerAmount / Math.max(1, containerAmount - containerSteps));
                    }
                }
            }

            final TreeSlot parent = peek();
            final TreeSlot known = parent != null
                ? this.childrenByKey.getOrDefault(parent, Collections.emptyMap()).get(mergeKey(ingrItem, prefItem))
                : null;
            if (known != null) {
                known.addOutput(ingrItem, prefItem, 0, requestedAmount, produced, containerAmount, isContainerItem);
                this.lastItem = known;
                return;
            }

            final TreeSlot slot = new TreeSlot(ingrItem, 0, requestedAmount, produced, containerAmount, prefItem, isContainerItem);
            addMissingResults(slot, prefItem);
            this.items.add(slot);
            this.parentOf.put(slot, parent);
            this.childrenOf.computeIfAbsent(parent, k -> new ArrayList<>())
                .add(slot);
            if (parent != null) {
                this.childrenByKey.computeIfAbsent(parent, k -> new HashMap<>())
                    .put(mergeKey(ingrItem, prefItem), slot);
            }
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
        final TreeSlot parent = peek();
        final TreeSlot known = parent != null
            ? this.childrenByKey.getOrDefault(parent, Collections.emptyMap()).get(mergeKey(ingrItem, prefItem))
            : null;

        if (known != null) {
            // the maths visits an item of a node it already drew (issue #42); its recipe shows once
            this.lastItem = known;
            if (prefItem != null && !isForeignBoxBoundary(ingrItem) && !visited.contains(prefItem.recipeId)
                && !this.childrenOf.containsKey(known)) {
                visited.add(prefItem.recipeId);
                prepareIngredients(prefItem.recipeId, visited);
                visited.remove(prefItem.recipeId);
            }
            return;
        }

        final TreeSlot slot = new TreeSlot(ingrItem, 0, 0, 0, 0, prefItem);
        addMissingResults(slot, prefItem);

        this.items.add(slot);
        this.parentOf.put(slot, parent);
        this.childrenOf.computeIfAbsent(parent, k -> new ArrayList<>())
            .add(slot);
        if (parent != null) {
            this.childrenByKey.computeIfAbsent(parent, k -> new HashMap<>())
                .put(mergeKey(ingrItem, prefItem), slot);
        }
        this.lastItem = slot;

        if (prefItem != null && !isForeignBoxBoundary(ingrItem) && !visited.contains(prefItem.recipeId)) {
            visited.add(prefItem.recipeId);
            prepareIngredients(prefItem.recipeId, visited);
            visited.remove(prefItem.recipeId);
        }
    }
}
