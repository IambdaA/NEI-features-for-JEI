/*
 * Recipe chain maths for the NEI addon for JEI.
 *
 * Port of GTNH NotEnoughItems' codechicken.nei.recipe.chain.RecipeChainIterator
 * (https://github.com/GTNewHorizons/NotEnoughItems), originally by Chicken-Bones, GTNewHorizons and the
 * NotEnoughItems contributors. Licensed under the GNU Lesser General Public License, version 3 or (at your option)
 * any later version.
 */
package neiaddon.chain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a chain that has a {@linkplain ChainMath#createMasterRoot master root} from the top down: every
 * {@link #next()} gives the recipes (with how often to run them) that feed the previous level. Autocrafting runs a
 * level, then asks again with the new inventory, and repeats whole passes until a pass crafts nothing.
 */
public final class ChainIterator {

    /** Item id -> how many the player has. */
    public final Map<String, Long> inventory = new HashMap<>();
    private final Set<String> precessedRecipes = new HashSet<>();
    private final ChainMath math;
    private Set<String> topRecipes;
    private final Set<String> processedRecipes;
    private final List<ChainItem> initialItems;

    public ChainIterator(ChainMath math, List<ChainItem> initialItems) {
        this.math = math;
        this.topRecipes = new HashSet<>(math.outputRecipes.keySet());
        this.initialItems = new ArrayList<>(initialItems);
        this.processedRecipes = new HashSet<>(this.topRecipes);
    }

    public void updateInventory(Map<String, Long> mainInventory) {
        this.inventory.clear();
        this.inventory.putAll(mainInventory);
    }

    public Map<String, Long> next() {
        refreshInitialItems();
        this.math.refresh();

        final Set<String> skipRecipes = new HashSet<>();
        final Map<String, Long> rootRecipes = new HashMap<>();
        final HashMap<ChainItem, ChainItem> preferredItems = new HashMap<>(this.math.preferredItems);
        preferredItems.values()
            .removeIf(item -> item == null || item.amount() == 0 || this.precessedRecipes.contains(item.recipeId));
        preferredItems.keySet()
            .removeIf(item -> item.amount() == 0);

        for (Map.Entry<ChainItem, ChainItem> entry : preferredItems.entrySet()) {
            final ChainItem keyItem = entry.getKey();
            if (this.topRecipes.contains(keyItem.recipeId) && !skipRecipes.contains(keyItem.recipeId)) {
                final ChainItem item = entry.getValue();

                if (preferredItems.entrySet()
                    .stream()
                    .anyMatch(pref -> !this.processedRecipes.contains(pref.getKey().recipeId) && item.equals(pref.getValue()))) {
                    skipRecipes.add(item.recipeId);
                } else {
                    rootRecipes.put(item.recipeId, Math.max(rootRecipes.getOrDefault(item.recipeId, 0L), item.multiplier));
                }
            }
        }

        for (String recipeId : skipRecipes) {
            rootRecipes.remove(recipeId);
        }

        this.processedRecipes.addAll(rootRecipes.keySet());
        rootRecipes.values()
            .removeIf(amount -> amount == 0);
        this.topRecipes = rootRecipes.keySet();
        this.precessedRecipes.addAll(this.topRecipes);

        return rootRecipes;
    }

    public boolean hasNext() {
        return !this.topRecipes.isEmpty();
    }

    private void refreshInitialItems() {
        math.initialItems.clear();
        math.initialItems.addAll(initialItems);

        for (Map.Entry<String, Long> stack : this.inventory.entrySet()) {
            if (stack.getValue() > 0) {
                math.initialItems.add(new ChainItem(stack.getKey(), null, ChainItem.Type.ITEM, 1, stack.getValue(), null, null));
            }
        }
    }
}
