/*
 * Recipe chain maths for the NEI addon for JEI.
 *
 * Port of GTNH NotEnoughItems' codechicken.nei.recipe.chain.RecipeChainMath
 * (https://github.com/GTNewHorizons/NotEnoughItems), originally by Chicken-Bones, GTNewHorizons and the
 * NotEnoughItems contributors. Licensed under the GNU Lesser General Public License, version 3 or (at your option)
 * any later version.
 *
 * Left out on purpose (NEI handles them, this addon does not): drop chances and fluids (JEI gives no chance data on a
 * recipe slot, and the addon only knows items), GregTech's tool durability ({@code GT.ToolStats}).
 */
package neiaddon.chain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import neiaddon.chain.ContainerOps.Held;
import neiaddon.chain.ContainerOps.Use;

/**
 * Works out how many times every recipe of one crafting group has to run so that the top recipes get everything they
 * need. After {@link #refresh()} every {@link ChainItem} in {@link #recipeResults}, {@link #recipeIngredients} and
 * {@link #initialItems} carries the calculated {@link ChainItem#multiplier}; the amount shown is
 * {@code factor * multiplier}.
 */
public class ChainMath {

    /** Recipes nothing else in the group consumes (the top of the tree) with the multiplier they are run at. */
    public final Map<String, Long> outputRecipes = new LinkedHashMap<>();

    /** Plain items: what the player already has. */
    public final List<ChainItem> initialItems = new ArrayList<>();
    public final List<ChainItem> recipeIngredients = new ArrayList<>();
    public final List<ChainItem> recipeResults = new ArrayList<>();

    protected final Map<String, List<ChainItem>> ingredientsByRecipe = new HashMap<>();
    private final Map<String, List<ChainItem>> resultsByRecipe = new HashMap<>();

    /** Ingredient -> the result of another recipe in the group that supplies it. */
    public final Map<ChainItem, ChainItem> preferredItems = new HashMap<>();

    /** Result/ingredient/plain item -> how much of it the chain needs. */
    public final Map<ChainItem, Long> requiredAmount = new HashMap<>();

    /** The game side of container items; null (the default in tests) means no item leaves anything behind. */
    public static ContainerOps containerOps;

    /** Leftovers of the crafts (empty buckets, worn tools) and of the items the player already has. */
    public final List<Held> containerItemsCrafting = new ArrayList<>();
    public final List<Held> containerItemsInventory = new ArrayList<>();

    private final List<Object> containerItemsBlacklist = new ArrayList<>();

    protected ChainMath(List<ChainItem> recipeItems, Set<String> collapsedRecipes) {
        final Map<String, Long> multipliers = new LinkedHashMap<>();

        for (ChainItem item : recipeItems) {
            if (item.recipeId == null || item.type == ChainItem.Type.ITEM) {
                this.initialItems.add(item.copyWithMultiplier(item.multiplier));
            } else if (item.type == ChainItem.Type.INGREDIENT) {
                this.recipeIngredients.add(item.copyWithMultiplier(0));
            } else {
                this.recipeResults.add(item.copyWithMultiplier(0));
                multipliers.merge(item.recipeId, item.multiplier, Math::max);
            }
        }

        rebuildRecipeIndex();

        for (Map.Entry<String, Long> entry : multipliers.entrySet()) {
            // a collapsed recipe is always an output recipe of its own, whatever its multiplier
            if (entry.getValue() > 1 || collapsedRecipes.contains(entry.getKey())) {
                collectPreferredItems(entry.getKey(), this.preferredItems, new HashSet<>());
                removeLoop(entry.getKey(), this.preferredItems, new HashSet<>());
                this.outputRecipes.put(entry.getKey(), entry.getValue());
            }
        }

        while (true) {
            Map<ChainItem, ChainItem> maxReference = Collections.emptyMap();
            String maxRecipeId = null;
            long maxMultiplier = 0;
            int maxDepth = 0;

            for (Map.Entry<String, Long> entry : multipliers.entrySet()) {
                final String recipeId = entry.getKey();
                if (!this.outputRecipes.containsKey(recipeId) && this.preferredItems.values()
                    .stream()
                    .noneMatch(resItem -> resItem.recipeId.equals(recipeId))) {
                    final Map<ChainItem, ChainItem> references = new HashMap<>(this.preferredItems);
                    collectPreferredItems(recipeId, references, new HashSet<>());
                    removeLoop(recipeId, references, new HashSet<>());
                    final int depth = getMaxDepth(recipeId, references, new HashSet<>());

                    if (maxDepth < depth || maxDepth == depth && entry.getValue() > maxMultiplier) {
                        maxMultiplier = entry.getValue();
                        maxReference = references;
                        maxRecipeId = recipeId;
                        maxDepth = depth;
                    }
                }
            }

            if (maxReference.isEmpty()) {
                break;
            }

            this.preferredItems.putAll(maxReference);
            this.outputRecipes.put(maxRecipeId, multipliers.get(maxRecipeId));
        }

        for (Map.Entry<String, Long> entry : multipliers.entrySet()) {
            final String recipeId = entry.getKey();
            final boolean isOutputRecipe = this.outputRecipes.containsKey(recipeId);
            final boolean recipeInMiddle = this.preferredItems.values()
                .stream()
                .anyMatch(resItem -> resItem.recipeId.equals(recipeId));

            if (!isOutputRecipe && !recipeInMiddle) {
                this.outputRecipes.put(recipeId, entry.getValue());
            } else if (isOutputRecipe && recipeInMiddle) {
                this.outputRecipes.put(recipeId, Math.max(0, entry.getValue() - 1));
            }
        }
    }

    /** The made-up recipe autocrafting wraps around the whole chain (NEI's ROOT_RECIPE_ID). */
    public static final String ROOT_RECIPE_ID = "recipe-autocrafting";
    private static final String ROOT_ITEM = "neiaddon:autocrafting_root";

    /**
     * NEI's {@code createMasterRoot}: every output recipe's results become the ingredients of one made-up recipe, which
     * is then the only output recipe. Safe to call again. Returns the root's id.
     */
    public String createMasterRoot() {
        final List<ChainItem> rootIngredients = new ArrayList<>();

        for (ChainItem item : this.recipeResults) {
            if (this.outputRecipes.containsKey(item.recipeId) && !ROOT_RECIPE_ID.equals(item.recipeId)) {
                final long amount = item.amount(this.outputRecipes.get(item.recipeId));
                rootIngredients.add(new ChainItem(item.item, ROOT_RECIPE_ID, ChainItem.Type.INGREDIENT, amount, 1, null, null));
            }
        }

        this.outputRecipes.clear();
        this.outputRecipes.put(ROOT_RECIPE_ID, 1L);
        this.recipeResults.removeIf(item -> ROOT_RECIPE_ID.equals(item.recipeId));
        this.recipeResults.add(new ChainItem(ROOT_ITEM, ROOT_RECIPE_ID, ChainItem.Type.RESULT, 1, 0, null, null));
        this.recipeIngredients.addAll(rootIngredients);
        rebuildRecipeIndex();

        return ROOT_RECIPE_ID;
    }

    public static ChainMath of(List<ChainItem> chainItems) {
        return new ChainMath(chainItems, Collections.emptySet());
    }

    /** NEI's {@code RecipeChainMath.of(items, collapsedRecipes)}. */
    public static ChainMath of(List<ChainItem> chainItems, Set<String> collapsedRecipes) {
        return new ChainMath(chainItems, collapsedRecipes);
    }

    private void rebuildRecipeIndex() {
        this.ingredientsByRecipe.clear();
        this.resultsByRecipe.clear();

        for (ChainItem item : this.recipeIngredients) {
            this.ingredientsByRecipe.computeIfAbsent(item.recipeId, k -> new ArrayList<>())
                .add(item);
        }

        for (ChainItem item : this.recipeResults) {
            this.resultsByRecipe.computeIfAbsent(item.recipeId, k -> new ArrayList<>())
                .add(item);
        }
    }

    /**
     * For every ingredient of the recipe, finds the result of another recipe that supplies it. Prefers a result of
     * the very same item, otherwise the one that makes the larger amount. Then does the same for that recipe.
     */
    private void collectPreferredItems(String recipeId, Map<ChainItem, ChainItem> preferredItems,
        Set<String> visited) {

        visited.add(recipeId);

        for (ChainItem ingrItem : this.ingredientsByRecipe.getOrDefault(recipeId, Collections.emptyList())) {
            if (!ingrItem.emptyFactor() && !preferredItems.containsKey(ingrItem)) {
                ChainItem activeItem = null;
                ChainItem prefItem = null;

                for (ChainItem item : this.recipeResults) {
                    if (!item.emptyFactor() && !visited.contains(item.recipeId) && item.containsItems(ingrItem)) {

                        if ((activeItem == null || item.amount(1) > activeItem.amount(1))
                            && ingrItem.item.equals(item.item)) {
                            activeItem = item;
                        } else if (prefItem == null || item.amount(1) > prefItem.amount(1)) {
                            prefItem = item;
                        }
                    }
                }

                if (activeItem != null) {
                    prefItem = activeItem;
                }

                if (prefItem != null) {
                    preferredItems.put(ingrItem, prefItem);
                    collectPreferredItems(prefItem.recipeId, preferredItems, visited);
                }
            }
        }

        visited.remove(recipeId);
    }

    private int getMaxDepth(String recipeId, Map<ChainItem, ChainItem> preferredItems, Set<String> visiting) {
        if (!visiting.add(recipeId)) {
            return 0;
        }

        int maxDepth = 0;

        for (ChainItem ingrItem : preferredItems.keySet()) {
            if (!ingrItem.emptyFactor() && recipeId.equals(ingrItem.recipeId)) {
                maxDepth = Math.max(maxDepth, getMaxDepth(preferredItems.get(ingrItem).recipeId, preferredItems, visiting) + 1);
            }
        }

        visiting.remove(recipeId);
        return maxDepth;
    }

    /** Never follow a loop: drops the link that would lead back to a recipe already on the way. */
    private void removeLoop(String recipeId, Map<ChainItem, ChainItem> preferredItems, Set<String> visited) {
        visited.add(recipeId);

        for (ChainItem ingrItem : this.ingredientsByRecipe.getOrDefault(recipeId, Collections.emptyList())) {
            if (!ingrItem.emptyFactor() && preferredItems.containsKey(ingrItem)) {
                ChainItem prefItem = preferredItems.get(ingrItem);

                if (visited.contains(prefItem.recipeId)) {
                    preferredItems.remove(ingrItem);
                } else {
                    removeLoop(prefItem.recipeId, preferredItems, visited);
                }
            }
        }

        visited.remove(recipeId);
    }

    /** Output recipes ordered so that those another output recipe depends on come first. */
    private List<String> getOutputRecipeProcessingOrder() {

        if (this.outputRecipes.size() <= 1) {
            return new ArrayList<>(this.outputRecipes.keySet());
        }

        final Map<String, Set<String>> dependents = new HashMap<>();
        final Map<String, Integer> inDegree = new HashMap<>();

        for (String recipeId : this.outputRecipes.keySet()) {
            inDegree.put(recipeId, 0);
        }

        for (String recipeId : this.outputRecipes.keySet()) {
            final Set<String> dependencies = new HashSet<>();
            collectBoxDependencies(recipeId, recipeId, dependencies, new HashSet<>());
            dependents.put(recipeId, dependencies);

            for (String dependency : dependencies) {
                inDegree.merge(dependency, 1, Integer::sum);
            }
        }

        final List<String> order = new ArrayList<>();
        final ArrayDeque<String> queue = new ArrayDeque<>();

        for (String recipeId : this.outputRecipes.keySet()) {
            if (inDegree.get(recipeId) == 0) {
                queue.add(recipeId);
            }
        }

        while (!queue.isEmpty()) {
            final String recipeId = queue.poll();
            order.add(recipeId);

            for (String dependency : dependents.getOrDefault(recipeId, Collections.emptySet())) {
                if (inDegree.merge(dependency, -1, Integer::sum) == 0) {
                    queue.add(dependency);
                }
            }
        }

        if (order.size() != this.outputRecipes.size()) {
            for (String recipeId : this.outputRecipes.keySet()) {
                if (!order.contains(recipeId)) {
                    order.add(recipeId);
                }
            }
        }

        return order;
    }

    private void collectBoxDependencies(String boxId, String currentRecipeId, Set<String> dependencies,
        Set<String> visited) {

        if (!visited.add(currentRecipeId)) {
            return;
        }

        for (ChainItem ingrItem : this.ingredientsByRecipe.getOrDefault(currentRecipeId, Collections.emptyList())) {
            if (ingrItem.emptyFactor()) {
                continue;
            }

            final ChainItem prefItem = this.preferredItems.get(ingrItem);
            if (prefItem == null) {
                continue;
            }

            if (this.outputRecipes.containsKey(prefItem.recipeId) && !prefItem.recipeId.equals(boxId)) {
                dependencies.add(prefItem.recipeId);
            } else {
                collectBoxDependencies(boxId, prefItem.recipeId, dependencies, visited);
            }
        }

        visited.remove(currentRecipeId);
    }

    /** What the chain still needs from outside: leftover results, raw ingredients and unused plain items. */
    public Map<String, Long> getMissedItems() {
        final Map<String, Long> missed = new LinkedHashMap<>();

        for (ChainItem item : this.recipeResults) {
            long amount = item.amount() - this.requiredAmount.getOrDefault(item, 0L);
            if (amount > 0) {
                missed.merge(item.item, amount, Long::sum);
            }
        }

        for (ChainItem item : this.recipeIngredients) {
            long amount = this.requiredAmount.containsKey(this.preferredItems.get(item)) ? 0
                : this.requiredAmount.getOrDefault(item, item.amount());
            if (amount > 0) {
                missed.merge(item.item, amount, Long::sum);
            }
        }

        for (ChainItem item : this.initialItems) {
            if (this.requiredAmount.getOrDefault(item, -1L) == 0) {
                missed.merge(item.item, item.amount(), Long::sum);
            }
        }

        return missed;
    }

    protected boolean isForeignBoxBoundary(ChainItem ingrItem) {
        final ChainItem prefItem = this.preferredItems.get(ingrItem);
        return prefItem != null && ingrItem.type != ChainItem.Type.RESULT
            && this.outputRecipes.containsKey(prefItem.recipeId);
    }

    private void resetCalculation() {

        for (ChainItem item : this.recipeIngredients) {
            item.multiplier = 0;
        }

        for (ChainItem item : this.recipeResults) {
            item.multiplier = 0;
        }

        this.preferredItems.clear();
        this.requiredAmount.clear();
        this.containerItemsCrafting.clear();
        this.containerItemsInventory.clear();
        this.containerItemsBlacklist.clear();

        for (String recipeId : this.outputRecipes.keySet()) {
            collectPreferredItems(recipeId, this.preferredItems, new HashSet<>());
            removeLoop(recipeId, this.preferredItems, new HashSet<>());
        }
    }

    public ChainMath refresh() {
        resetCalculation();

        final ContainerOps ops = containerOps;
        if (ops != null && this.outputRecipes.containsKey(ROOT_RECIPE_ID)) {
            for (ChainItem ingrItem : this.recipeIngredients) {
                if (ROOT_RECIPE_ID.equals(ingrItem.recipeId)) {
                    final Object stack = ops.stackOf(ingrItem);
                    if (stack != null && ops.hasContainerItem(stack)) {
                        this.containerItemsBlacklist.add(stack);
                    }
                }
            }
        }

        for (String recipeId : getOutputRecipeProcessingOrder()) {
            final long prefMultiplier = this.outputRecipes.get(recipeId);

            for (ChainItem prefItem : this.resultsByRecipe.getOrDefault(recipeId, Collections.emptyList())) {
                if (prefItem.emptyFactor()) {
                    continue;
                }

                if (ops != null) {
                    final Object stack = ops.stackOf(prefItem);
                    if (stack != null && ops.hasContainerItem(stack)) {
                        this.containerItemsBlacklist.add(stack);
                    }
                }

                this.preferredItems.put(prefItem, prefItem);
                calculateSuitableRecipe(prefItem, prefMultiplier, new ArrayList<>());
                this.preferredItems.remove(prefItem);
            }
        }

        for (ChainItem prefItem : this.recipeResults) {
            if (!prefItem.emptyFactor() && this.outputRecipes.containsKey(prefItem.recipeId)
                && this.requiredAmount.containsKey(prefItem)) {
                final long prefAmount = prefItem.amount(this.outputRecipes.get(prefItem.recipeId));
                this.requiredAmount.put(prefItem, this.requiredAmount.get(prefItem) - prefAmount);
            }
        }

        return this;
    }

    protected void calculateSuitableRecipe(ChainItem ingrItem, long ingrMultiplier, List<String> visited) {
        final ChainItem prefItem = this.preferredItems.get(ingrItem);
        long ingrAmount = ingrItem.amount(ingrMultiplier);
        final long requested = ingrAmount;
        long containerAmount = 0;
        long inventoryAmount = 0;

        // calculate existing containers (a tool an earlier craft left behind is used again)
        final ContainerOps ops = containerOps;
        if (ops != null && ingrAmount > 0) {
            for (String option : ingrItem.options) {
                final Object stack = option.equals(ingrItem.item) ? firstNonNull(ops.stackOf(ingrItem), ops.stackOf(option))
                    : ops.stackOf(option);
                if (stack != null && hasContainerItem(stack)) {
                    long stackSize = ingrAmount;
                    long shiftSize = shiftContainerItems(stack, stackSize, this.containerItemsCrafting);

                    if (stackSize != shiftSize) {
                        containerAmount += ingrAmount - shiftSize;
                        if ((ingrAmount = shiftSize) == 0) {
                            break;
                        }
                    }

                    stackSize = shiftSize;
                    shiftSize = shiftContainerItems(stack, stackSize, this.containerItemsInventory);

                    if (stackSize != shiftSize) {
                        inventoryAmount += ingrAmount - shiftSize;
                        if ((ingrAmount = shiftSize) == 0) {
                            break;
                        }
                    }
                }
            }
        }

        // the player's plain items are spent first
        if (ingrAmount > 0) {
            for (ChainItem item : this.initialItems) {
                if (item.containsItems(ingrItem)) {
                    final long newIngrAmount = addRequiredAmount(item, ingrAmount, item.amount(), this.containerItemsInventory);
                    inventoryAmount += ingrAmount - newIngrAmount;
                    ingrAmount = newIngrAmount;
                    if (ingrAmount == 0) {
                        break;
                    }
                }
            }
        }

        final ChainItem producer = prefItem != null ? prefItem : ingrItem;
        final long beforeShift = this.requiredAmount.getOrDefault(producer, 0L);
        addRequiredAmount(producer, ingrAmount, Long.MAX_VALUE, this.containerItemsCrafting);

        calculationStepTrigger(ingrItem, prefItem, requested, containerAmount, inventoryAmount, ingrAmount,
            this.requiredAmount.getOrDefault(producer, 0L) - beforeShift);

        if (prefItem != null && !isForeignBoxBoundary(ingrItem) && !visited.contains(prefItem.recipeId)) {
            final long multiplier = Math
                .max(0, prefItem.multiplierFromAmount(this.requiredAmount.get(prefItem)) - prefItem.multiplier);

            if (multiplier > 0) {
                addShift(prefItem.recipeId, multiplier);
                visited.add(prefItem.recipeId);
                prepareIngredients(prefItem.recipeId, multiplier, visited);
                visited.remove(prefItem.recipeId);
            }
        }
    }

    /** NEI's hook, called for every item the maths visits (the crafting tree builds its nodes here). */
    protected void calculationStepTrigger(ChainItem ingrItem, ChainItem prefItem, long requested, long containerAmount,
        long inventoryAmount, long ingrAmount, long produced) {}

    protected void prepareIngredients(String recipeId, long multiplier, List<String> visited) {
        for (ChainItem item : this.ingredientsByRecipe.getOrDefault(recipeId, Collections.emptyList())) {
            if (!item.emptyFactor()) {
                calculateSuitableRecipe(item, multiplier, visited);
            }
        }
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    /** NEI's hasContainerItem: the item leaves something behind and is not one of the chain's own results. */
    protected boolean hasContainerItem(Object stack) {
        final ContainerOps ops = containerOps;

        if (ops != null && ops.hasContainerItem(stack)) {
            for (Object bStack : this.containerItemsBlacklist) {
                if (ops.sameForCrafting(stack, bStack)) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }

    /**
     * Adds up to {@code maxAmount - alreadyRequired} of {@code ingrAmount} to the item and returns what is left. An item
     * that leaves a container behind is counted in uses of the leftovers first.
     */
    protected long addRequiredAmount(ChainItem prefItem, long ingrAmount, long maxAmount, List<Held> containerItems) {
        long shiftAmount = this.requiredAmount.getOrDefault(prefItem, 0L);
        final ContainerOps ops = containerOps;
        Object itemStack = ops != null ? ops.stackOf(prefItem) : null;

        if (itemStack != null && hasContainerItem(itemStack)) {

            while (ingrAmount > 0 && shiftAmount < maxAmount) {
                long multiplier = 1;
                itemStack = ops.copy(itemStack);

                ingrAmount = shiftContainerItems(itemStack, ingrAmount, containerItems);

                if (ingrAmount > 0) {
                    long steps = ingrAmount;
                    final Use result = getToolsContainerItems(itemStack, steps);

                    if (result.stack == null) {
                        final long stepsPerReplacement = steps - result.leftSteps;
                        multiplier = Math.min(steps / stepsPerReplacement, maxAmount - shiftAmount);
                        steps -= multiplier * stepsPerReplacement;
                    } else {
                        steps = result.leftSteps;
                    }

                    if (result.containerItem != null) {
                        containerItems.add(new Held(result.containerItem, multiplier));
                    }

                    if (result.stack != null) {
                        containerItems.add(new Held(result.stack, 1));
                    }

                    ingrAmount = steps;
                }

                shiftAmount += multiplier;
            }

        } else {
            long initAmount = Math.min(ingrAmount, maxAmount - shiftAmount);

            shiftAmount += initAmount;
            ingrAmount -= initAmount;
        }

        this.requiredAmount.put(prefItem, shiftAmount);

        return ingrAmount;
    }

    /** NEI's shiftContainerItems: uses up leftovers of the same kind for {@code steps} crafts and returns the steps left. */
    private long shiftContainerItems(Object aStack, long steps, List<Held> containerItems) {
        final ContainerOps ops = containerOps;
        final int initialSize = containerItems.size();

        for (int i = 0; i < initialSize && steps > 0; i++) {
            final Held bStack = containerItems.get(i);

            if (bStack.stack != null && ops.sameForCrafting(aStack, bStack.stack)) {
                final Use result = getToolsContainerItems(bStack.stack, steps);

                bStack.stack = result.stack;
                steps = result.leftSteps;

                if (result.containerItem != null) {
                    containerItems.add(new Held(result.containerItem, 1));
                }
            }
        }

        containerItems.removeIf(held -> held.stack == null);

        return steps;
    }

    /** Most uses of one tool worked out one by one; a tool that never wears out stops here instead of looping forever. */
    private static final long MAX_USES_TRACKED = 1_000_000L;

    /**
     * NEI's getToolsContainerItems (without the GregTech tool stats): uses the stack once per step. A tool that comes
     * back as the same item (worn) is used again; one that comes back as another item or not at all ends the loop.
     */
    protected Use getToolsContainerItems(Object stack, long steps) {
        final ContainerOps ops = containerOps;
        Object aStack = stack;
        long tracked = 0;

        while (aStack != null && steps > 0) {
            aStack = ops.remainder(aStack);

            steps--;

            if (aStack != null && !ops.sameItem(stack, aStack)) {
                return new Use(null, steps, aStack);
            }

            if (++tracked >= MAX_USES_TRACKED && aStack != null) {
                return new Use(aStack, 0, null);
            }
        }

        return new Use(aStack, steps, null);
    }

    private void addShift(String recipeId, long shift) {
        for (ChainItem item : this.ingredientsByRecipe.getOrDefault(recipeId, Collections.emptyList())) {
            item.multiplier += shift;
        }

        for (ChainItem item : this.resultsByRecipe.getOrDefault(recipeId, Collections.emptyList())) {
            item.multiplier += shift;
        }
    }
}
