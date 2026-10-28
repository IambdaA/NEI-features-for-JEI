/*
 * Recipe chain maths for the NEI addon for JEI.
 *
 * Port of GTNH NotEnoughItems' codechicken.nei.bookmark.RecipeChainDetails
 * (https://github.com/GTNewHorizons/NotEnoughItems), originally by Chicken-Bones, GTNewHorizons and the
 * NotEnoughItems contributors. Licensed under the GNU Lesser General Public License, version 3 or (at your option)
 * any later version.
 */
package neiaddon.chain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The result of running {@link ChainMath} over one group: for every stored item (found again by its
 * {@link ChainItem#payload}) the calculated multiplier, what kind of item it is in the chain and the "real"
 * multiplier of its recipe.
 */
public final class ChainDetails {

    public enum Calculated {

        INGREDIENT,
        /** Result of an output recipe (the top of the tree). */
        RESULT,
        /** Result of a recipe in the middle of the tree. */
        REMAINDER;

        /** NEI's CalculatedType.toInt: the order merged slots are drawn in. */
        public int toInt() {
            switch (this) {
                case RESULT:
                    return 0;
                case REMAINDER:
                    return 1;
                default:
                    return 2;
            }
        }
    }

    public static final class Calc {

        /** The maths' copy; its {@link ChainItem#multiplier} is the calculated one. */
        public final ChainItem item;
        public Calculated type;
        /** The multiplier the player set; one less when the recipe is also used by another recipe. */
        public long realMultiplier;
        /** Collapsed recipes: how much of the item the merged slot stands for (NEI's getShiftAmount). */
        public long shiftAmount;
        /** Collapsed recipes: NEI's negative item index, a slot that merges items of the recipe and the ones it feeds on. */
        public boolean shadow;

        Calc(ChainItem item, Calculated type) {
            this.item = item;
            this.type = type;
        }

        /** NEI's {@code BookmarkChainItem.of(item, 0, 0, type)}: an empty merged slot for the item. */
        static Calc ofMerged(ChainItem item, Calculated type) {
            return new Calc(item.copyWithMultiplier(0), type);
        }

        /** NEI's {@code BookmarkChainItem.append}: adds to a merged slot. */
        void append(long amount, long calculatedAmount) {
            this.shiftAmount += amount;
            item.multiplier += item.multiplierFromAmount(calculatedAmount);
        }

        public long calculatedMultiplier() {
            return item.multiplier;
        }

        public long calculatedAmount() {
            return item.amount();
        }
    }

    /** Stored item payload -> its calculated values. */
    public final Map<Object, Calc> calculated = new IdentityHashMap<>();
    public final Set<String> outputRecipes = new HashSet<>();
    public final Set<String> recipeInMiddle = new HashSet<>();
    /** Collapsed recipe -> the recipes it swallows (itself included); only collapsed recipes have an entry. */
    public final Map<String, Set<String>> recipeRelations = new HashMap<>();
    /** Collapsed recipe -> the slots it is drawn with, unsorted: its own results and the merged shadow items. */
    public final Map<String, List<Calc>> collapsedItems = new HashMap<>();
    /** Stored item payload -> the collapsed recipe its slot belongs to. */
    public final Map<Object, String> itemToRecipe = new IdentityHashMap<>();
    public ChainMath math;

    public static ChainDetails of(List<ChainItem> items) {
        return of(items, Collections.emptySet());
    }

    public static ChainDetails of(List<ChainItem> items, Set<String> collapsedRecipes) {
        ChainDetails details = new ChainDetails();
        details.refresh(items, collapsedRecipes);
        return details;
    }

    public void refresh(List<ChainItem> items) {
        refresh(items, Collections.emptySet());
    }

    public void refresh(List<ChainItem> items, Set<String> collapsedRecipes) {
        calculated.clear();
        outputRecipes.clear();
        recipeInMiddle.clear();
        recipeRelations.clear();
        collapsedItems.clear();
        itemToRecipe.clear();

        math = ChainMath.of(new ArrayList<>(items), collapsedRecipes)
            .refresh();
        outputRecipes.addAll(math.outputRecipes.keySet());

        for (ChainItem item : math.preferredItems.values()) {
            if (item != null && item.recipeId != null) {
                recipeInMiddle.add(item.recipeId);
            }
        }

        for (ChainItem item : math.initialItems) {
            calculated.put(item.payload, new Calc(item, Calculated.INGREDIENT));
        }

        if (collapsedRecipes.isEmpty()) {
            for (ChainItem item : math.recipeResults) {
                generateResult(item);
            }

            for (ChainItem item : math.recipeIngredients) {
                generateIngredient(item);
            }
        } else {
            final Set<String> topLevelRecipes = findTopLevelRecipes(collapsedRecipes);

            for (String recipeId : collapsedRecipes) {
                recipeRelations.put(recipeId, getRecipeRelations(math, recipeId, topLevelRecipes, new HashSet<>(Arrays.asList(recipeId))));
                generateCollapsedRecipe(recipeId, topLevelRecipes);
                generateShadowItems(recipeId, items);
            }

            for (ChainItem item : math.recipeResults) {
                if (!collapsedRecipes.contains(item.recipeId) && topLevelRecipes.contains(item.recipeId)) {
                    generateResult(item);
                }
            }

            for (ChainItem item : math.recipeIngredients) {
                if (!collapsedRecipes.contains(item.recipeId) && topLevelRecipes.contains(item.recipeId)) {
                    generateIngredient(item);
                }
            }
        }

        for (ChainItem item : items) {
            Calc calc = calculated.get(item.payload);
            if (calc != null) {
                calc.realMultiplier = recipeInMiddle.contains(item.recipeId) ? Math.max(0, item.multiplier - 1)
                    : item.multiplier;
            }
        }
    }

    private void generateResult(ChainItem item) {
        calculated.put(item.payload, new Calc(item, outputRecipes.contains(item.recipeId) ? Calculated.RESULT : Calculated.REMAINDER));
    }

    private void generateIngredient(ChainItem item) {
        calculated.put(item.payload, new Calc(item, Calculated.INGREDIENT));
    }

    private static Set<String> getRecipeRelations(ChainMath math, String recipeId, Set<String> topLevelRecipes, Set<String> recipes) {
        for (ChainItem item : math.recipeIngredients) {
            if (item.recipeId.equals(recipeId)) {
                final ChainItem prefItem = math.preferredItems.get(item);
                if (prefItem != null && !topLevelRecipes.contains(prefItem.recipeId) && recipes.add(prefItem.recipeId)) {
                    getRecipeRelations(math, prefItem.recipeId, topLevelRecipes, recipes);
                }
            }
        }

        return recipes;
    }

    private void generateCollapsedRecipe(String recipeId, Set<String> topLevelRecipes) {
        final List<Calc> list = collectItems(math, recipeId, recipeRelations.get(recipeId), topLevelRecipes);

        for (Calc value : list) {
            value.shadow = value.item.type == ChainItem.Type.INGREDIENT || !recipeId.equals(value.item.recipeId);

            if (value.item.type == ChainItem.Type.RESULT && !math.outputRecipes.containsKey(value.item.recipeId)) {
                value.type = Calculated.REMAINDER;
            }

            collapsedItems.computeIfAbsent(recipeId, k -> new ArrayList<>())
                .add(value);
            if (!value.shadow) {
                itemToRecipe.put(value.item.payload, recipeId);
                calculated.put(value.item.payload, value);
            }
        }
    }

    /** The real multiplier of the merged slots: what the recipe and the ones it swallows need when nothing else is calculated. */
    private void generateShadowItems(String recipeId, List<ChainItem> chainItems) {
        final Set<String> relations = recipeRelations.get(recipeId);
        final List<ChainItem> subChainItems = new ArrayList<>();

        for (ChainItem item : chainItems) {
            if (relations.contains(item.recipeId)) {
                subChainItems.add(item.copyWithMultiplier(recipeId.equals(item.recipeId) ? item.multiplier : 0));
            }
        }

        final ChainMath sub = ChainMath.of(subChainItems, Collections.emptySet())
            .refresh();
        final List<Calc> list = collectItems(sub, recipeId, relations, Collections.emptySet());

        for (Calc value : list) {
            final boolean shadow = value.item.type == ChainItem.Type.INGREDIENT || !recipeId.equals(value.item.recipeId);

            for (Calc calc : collapsedItems.getOrDefault(recipeId, Collections.emptyList())) {
                if (calc.item.payload == value.item.payload && calc.shadow == shadow) {
                    calc.realMultiplier = value.item.multiplierFromAmount(value.shiftAmount);
                }
            }
        }
    }

    /** NEI's collectItems: the items of the recipe and its relations merged by item, same item adds up. */
    private static List<Calc> collectItems(ChainMath math, String recipeId, Set<String> recipeRelations, Set<String> topLevelRecipes) {
        final Map<String, Calc> items = new java.util.LinkedHashMap<>();
        final Map<String, Long> multiplier = new HashMap<>();

        for (String relRecipeId : recipeRelations) {
            for (ChainItem item : math.recipeResults) {
                if (item.recipeId.equals(relRecipeId)) {
                    final long amount = item.amount() - math.requiredAmount.getOrDefault(item, 0L);

                    if (item.recipeId.equals(recipeId)) {
                        multiplier.put(item.recipeId, item.multiplier);
                        items.computeIfAbsent(item.item, is -> Calc.ofMerged(item, Calculated.RESULT))
                            .append(amount, item.amount());
                    } else if (amount > 0) {
                        items.computeIfAbsent(item.item, is -> Calc.ofMerged(item, Calculated.RESULT))
                            .append(amount, item.amount());
                    }
                }
            }

            for (ChainItem item : math.recipeIngredients) {
                if (item.recipeId.equals(relRecipeId)) {
                    final ChainItem prefItem = math.preferredItems.get(item);
                    final long amount = math.requiredAmount.containsKey(prefItem) ? 0 : math.requiredAmount.getOrDefault(item, item.amount());
                    final long refAmount = prefItem != null && !topLevelRecipes.contains(prefItem.recipeId)
                        ? math.requiredAmount.getOrDefault(prefItem, 0L)
                        : 0;

                    if (amount != 0 || item.amount() > refAmount && math.requiredAmount.containsKey(prefItem)
                        || recipeId.equals(item.recipeId) && multiplier.getOrDefault(recipeId, 0L) == 0) {
                        items.computeIfAbsent(item.item, is -> Calc.ofMerged(item, Calculated.INGREDIENT))
                            .append(amount, item.amount() - refAmount);
                    }
                }
            }
        }

        return new ArrayList<>(items.values());
    }

    private Set<String> findTopLevelRecipes(Set<String> collapsedRecipes) {
        final Set<String> result = new HashSet<>(math.outputRecipes.keySet());

        for (ChainItem item : math.recipeResults) {
            if (!result.contains(item.recipeId)) {
                final Set<String> parents = getRecipeParents(item.recipeId, new HashSet<>(), new HashSet<>(Arrays.asList(item.recipeId)));

                if (parents.size() != 1 || parents.stream()
                    .noneMatch(collapsedRecipes::contains)) {
                    result.add(item.recipeId);
                }
            }
        }

        return result;
    }

    private Set<String> getRecipeParents(String recipeId, Set<String> parents, Set<String> visited) {
        final Set<String> recipeIds = math.preferredItems.entrySet()
            .stream()
            .filter(entry -> entry.getValue() != null && recipeId.equals(entry.getValue().recipeId))
            .map(entry -> entry.getKey().recipeId)
            .collect(Collectors.toSet());

        if (recipeIds.isEmpty()) {
            parents.add(recipeId);
        } else {
            for (String ingrRecipeId : recipeIds) {
                if (visited.add(ingrRecipeId)) {
                    if (math.outputRecipes.containsKey(ingrRecipeId)) {
                        parents.add(ingrRecipeId);
                    } else {
                        getRecipeParents(ingrRecipeId, parents, visited);
                    }
                }
            }
        }

        return parents;
    }
}
