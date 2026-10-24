/*
 * Recipe chain maths for the NEI addon for JEI.
 *
 * Port of GTNH NotEnoughItems' codechicken.nei.recipe.chain.RecipeChainMath
 * (https://github.com/GTNewHorizons/NotEnoughItems), originally by Chicken-Bones, GTNewHorizons and the
 * NotEnoughItems contributors. Licensed under the GNU Lesser General Public License, version 3 or (at your option)
 * any later version.
 *
 * Left out on purpose (NEI handles them, this addon does not): container items (buckets, damageable tools), drop
 * chances, fluids, collapsing recipes inside a chain.
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

        for (String recipeId : this.outputRecipes.keySet()) {
            collectPreferredItems(recipeId, this.preferredItems, new HashSet<>());
            removeLoop(recipeId, this.preferredItems, new HashSet<>());
        }
    }

    public ChainMath refresh() {
        resetCalculation();

        for (String recipeId : getOutputRecipeProcessingOrder()) {
            final long prefMultiplier = this.outputRecipes.get(recipeId);

            for (ChainItem prefItem : this.resultsByRecipe.getOrDefault(recipeId, Collections.emptyList())) {
                if (prefItem.emptyFactor()) {
                    continue;
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
        long inventoryAmount = 0;

        // the player's plain items are spent first
        if (ingrAmount > 0) {
            for (ChainItem item : this.initialItems) {
                if (item.containsItems(ingrItem)) {
                    final long newIngrAmount = addRequiredAmount(item, ingrAmount, item.amount());
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
        addRequiredAmount(producer, ingrAmount, Long.MAX_VALUE);

        calculationStepTrigger(ingrItem, prefItem, requested, 0, inventoryAmount, ingrAmount,
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

    /** Adds up to {@code maxAmount - alreadyRequired} of {@code ingrAmount} to the item and returns what is left. */
    private long addRequiredAmount(ChainItem prefItem, long ingrAmount, long maxAmount) {
        long shiftAmount = this.requiredAmount.getOrDefault(prefItem, 0L);
        long initAmount = Math.min(ingrAmount, maxAmount - shiftAmount);

        shiftAmount += initAmount;
        ingrAmount -= initAmount;

        this.requiredAmount.put(prefItem, shiftAmount);

        return ingrAmount;
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
