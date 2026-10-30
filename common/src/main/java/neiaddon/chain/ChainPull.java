package neiaddon.chain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What pulling items takes out of storage: the maths half of NEI's BookmarkPanel.pullBookmarkItems. No Minecraft
 * classes, so it is tested with a plain javac (see tools/PullCheck.java).
 */
public final class ChainPull {

    private ChainPull() {}

    /**
     * @param inventory what the player carries (empty unless Shift is held, so only the missing items are pulled)
     * @param stored    what the container holds
     * @return item -> amount to take out of the container
     */
    public static Map<String, Long> plan(ChainMath math, Map<String, Long> inventory, Map<String, Long> stored) {
        final Map<String, Long> pull = new LinkedHashMap<>();

        if (math.outputRecipes.isEmpty()) {
            for (ChainItem item : math.initialItems) {
                final long amount = item.amount() - inventory.getOrDefault(item.item, 0L);
                if (amount > 0) pull.merge(item.item, amount, Long::sum);
            }
            return pull;
        }

        final List<ChainItem> initial = new ArrayList<>();
        for (ChainItem item : math.initialItems) {
            final long amount = item.amount() - inventory.getOrDefault(item.item, 0L) - stored.getOrDefault(item.item, 0L);
            if (amount > 0) initial.add(item.copyWithMultiplier(amount));
        }
        math.initialItems.clear();
        math.initialItems.addAll(initial);
        inventory.forEach(
            (id, amount) -> math.initialItems.add(new ChainItem(id, null, ChainItem.Type.ITEM, 1, amount, null, null, -1)));
        stored.forEach(
            (id, amount) -> math.initialItems.add(new ChainItem(id, null, ChainItem.Type.ITEM, 1, amount, null, null, -2)));
        math.refresh();

        for (ChainItem item : math.initialItems) {
            final Long required = item.source == -2 ? math.requiredAmount.get(item) : null;
            if (required != null && required > 0) pull.merge(item.item, required, Long::sum);
        }
        return pull;
    }
}
