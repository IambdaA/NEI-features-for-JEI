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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What NEI's StatsPanel shows under the tree, as plain data: every section is a list of {@link Row}s. */
public final class TreeStats {

    public enum Kind {

        INGREDIENT_NEEDED,
        INGREDIENT_AVAILABLE,
        CRAFT_NEEDED,
        CRAFT_AVAILABLE,
        RESULT,
        REMAINDER,
        HANDLER
    }

    /** One slot of the panel. {@code item} is the registry id, {@code payload} the caller's object of an item that has it. */
    public static final class Row {

        public final Kind kind;
        public final String item;
        public final long amount;
        public final Object payload;
        /** Crafting rows: the recipe and how often it runs. */
        public final String recipeId;
        public final long multiplier;
        /** Handler rows: the recipe type and the totals. */
        public final String handler;
        public final long iterations;
        public final long recipeCount;

        Row(Kind kind, String item, long amount, Object payload, String recipeId, long multiplier, String handler,
            long iterations, long recipeCount) {
            this.kind = kind;
            this.item = item;
            this.amount = amount;
            this.payload = payload;
            this.recipeId = recipeId;
            this.multiplier = multiplier;
            this.handler = handler;
            this.iterations = iterations;
            this.recipeCount = recipeCount;
        }
    }

    private TreeStats() {}

    /** The recipe type of a recipe id: what comes before the first {@code |} or {@code #} (NEI's getHandlerName). */
    public static String handlerOf(String recipeId) {
        if (recipeId == null) return null;
        int bar = recipeId.indexOf('|');
        int hash = recipeId.indexOf('#');
        int sep = bar >= 0 && (hash < 0 || bar < hash) ? bar : hash;
        return sep > 0 ? recipeId.substring(0, sep) : recipeId;
    }

    private static List<Row> merged(Kind kind, Map<String, Long> amounts, Map<String, Object> payloads) {
        final List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : amounts.entrySet()) {
            if (e.getValue() > 0) rows.add(new Row(kind, e.getKey(), e.getValue(), payloads.get(e.getKey()), null, 0, null, 0, 0));
        }
        return rows;
    }

    /** Ingredients that have to be gathered (nothing in the chain makes them). */
    public static List<Row> inputs(ChainMath math) {
        final Map<String, Long> amounts = new LinkedHashMap<>();
        final Map<String, Object> payloads = new HashMap<>();
        for (ChainItem item : math.recipeIngredients) {
            final long amount = math.requiredAmount.containsKey(math.preferredItems.get(item)) ? 0
                : math.requiredAmount.getOrDefault(item, item.amount());
            if (amount > 0) {
                amounts.merge(item.item, amount, Long::sum);
                payloads.putIfAbsent(item.item, item.payload);
            }
        }
        return merged(Kind.INGREDIENT_NEEDED, amounts, payloads);
    }

    /** Items the player already has (plain bookmarks or the inventory) that the chain uses. */
    public static List<Row> available(ChainMath math) {
        final Map<String, Long> amounts = new LinkedHashMap<>();
        final Map<String, Object> payloads = new HashMap<>();
        for (ChainItem item : math.initialItems) {
            final long amount = math.requiredAmount.getOrDefault(item, 0L);
            if (amount > 0) {
                amounts.merge(item.item, amount, Long::sum);
                payloads.putIfAbsent(item.item, item.payload);
            }
        }
        return merged(Kind.INGREDIENT_AVAILABLE, amounts, payloads);
    }

    public static List<Row> outputs(ChainMath math) {
        final Map<String, Long> amounts = new LinkedHashMap<>();
        final Map<String, Object> payloads = new HashMap<>();
        for (ChainItem item : math.recipeResults) {
            final long amount = item.amount() - math.requiredAmount.getOrDefault(item, 0L);
            if (amount > 0 && math.outputRecipes.containsKey(item.recipeId)) {
                amounts.merge(item.item, amount, Long::sum);
                payloads.putIfAbsent(item.item, item.payload);
            }
        }
        return merged(Kind.RESULT, amounts, payloads);
    }

    public static List<Row> remainders(ChainMath math) {
        final Map<String, Long> amounts = new LinkedHashMap<>();
        final Map<String, Object> payloads = new HashMap<>();
        for (ChainItem item : math.recipeResults) {
            final long amount = item.amount() - math.requiredAmount.getOrDefault(item, 0L);
            if (amount > 0 && !math.outputRecipes.containsKey(item.recipeId)) {
                amounts.merge(item.item, amount, Long::sum);
                payloads.putIfAbsent(item.item, item.payload);
            }
        }
        final ContainerOps ops = ChainMath.containerOps;
        if (ops != null) {
            for (List<ContainerOps.Held> held : List.of(math.containerItemsInventory, math.containerItemsCrafting)) {
                for (ContainerOps.Held h : held) {
                    if (h.stack == null) continue;
                    final String id = ops.idOf(h.stack);
                    amounts.merge(id, h.count, Long::sum);
                    payloads.putIfAbsent(id, h.stack);
                }
            }
        }
        return merged(Kind.REMAINDER, amounts, payloads);
    }

    /** One row per recipe type: how many recipes and how many runs of them. */
    public static List<Row> handlers(ChainMath math) {
        final Map<String, long[]> stats = new LinkedHashMap<>();
        final Set<String> counted = new HashSet<>();
        for (ChainItem item : math.recipeResults) {
            // a recipe adds one result item per output, count it only once
            if (!counted.add(item.recipeId)) continue;
            final long[] s = stats.computeIfAbsent(handlerOf(item.recipeId), k -> new long[2]);
            s[0] += item.multiplier;
            s[1]++;
        }
        final List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : stats.entrySet()) {
            rows.add(new Row(Kind.HANDLER, null, 0, null, null, 0, e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return rows;
    }

    /** Recipes to craft: first the ones that still lack ingredients, then the ones that can be crafted right away. */
    public static List<Row> crafting(TreeGraph graph, boolean showNeeded, boolean showAvailable) {
        final Map<ChainItem, Boolean> recipeMissing = new LinkedHashMap<>();
        final Map<ChainItem, Integer> recipeLevel = new HashMap<>();

        for (Map.Entry<TreeSlot, List<TreeSlot>> entry : graph.childrenOf.entrySet()) {
            final TreeSlot node = entry.getKey();
            if (node == null || node.prefItem == null) continue;
            final boolean needsCrafting = graph.childrenNeedCraftingCache.contains(node);
            recipeMissing.merge(node.prefItem, needsCrafting, (a, b) -> a || b);
            recipeLevel.merge(node.prefItem, treeLevel(node, graph.parentOf), Math::max);
        }

        final List<Map.Entry<ChainItem, Boolean>> sorted = new ArrayList<>(recipeMissing.entrySet());
        sorted.sort(
            Comparator.<Map.Entry<ChainItem, Boolean>>comparingInt(e -> recipeLevel.get(e.getKey()))
                .thenComparing(e -> e.getKey().item));

        final List<Row> needed = new ArrayList<>();
        final List<Row> available = new ArrayList<>();
        for (Map.Entry<ChainItem, Boolean> entry : sorted) {
            final ChainItem result = entry.getKey();
            final long amount = result.amount();
            if (amount <= 0) continue;
            final boolean missing = entry.getValue();
            if (missing ? !showNeeded : !showAvailable) continue;
            (missing ? needed : available).add(
                new Row(missing ? Kind.CRAFT_NEEDED : Kind.CRAFT_AVAILABLE, result.item, amount, result.payload,
                    result.recipeId, result.multiplier, null, 0, 0));
        }
        needed.addAll(available);
        return needed;
    }

    private static int treeLevel(TreeSlot node, Map<TreeSlot, TreeSlot> parentOf) {
        int level = 0;
        for (TreeSlot current = parentOf.get(node); current != null; current = parentOf.get(current)) level++;
        return level;
    }
}
