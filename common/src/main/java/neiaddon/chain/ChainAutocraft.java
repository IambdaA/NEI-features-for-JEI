/*
 * Recipe chain maths for the NEI addon for JEI.
 *
 * The control flow of GTNH NotEnoughItems' codechicken.nei.recipe.AutoCraftingManager (the task's execute()), turned
 * from loops into a step-by-step walker so the game side can wait between crafts. Licensed under the GNU Lesser
 * General Public License, version 3 or (at your option) any later version.
 */
package neiaddon.chain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides what to craft next. Call {@link #next()} for a recipe id to craft once, craft it (and let the inventory
 * settle), call {@link #crafted()}, and ask again; {@code null} means finished. {@link #abandon()} gives up on the
 * current recipe (the craft failed) and moves on.
 */
public final class ChainAutocraft {

    public interface Host {

        /** Item id -> count of everything the player can craft with right now. */
        Map<String, Long> inventory();

        /** Can the recipe be crafted once right now (a crafting grid is open, every ingredient is present)? */
        boolean canCraft(String recipeId);
    }

    private enum Phase {
        PASS,
        BATCH,
        ENTRY,
        STEP
    }

    private final ChainMath math;
    private final Host host;
    private final List<ChainItem> initialItems;
    private Phase phase = Phase.PASS;
    private ChainIterator iterator;
    private List<Map.Entry<String, Long>> batch;
    private int index;
    private String current;
    private long remaining;
    private boolean entryCrafted, batchCrafted, changed, processed;

    /** @param math a chain that already has its master root */
    public ChainAutocraft(ChainMath math, Host host) {
        this.math = math;
        this.host = host;
        this.initialItems = prepareInitialItems(math, host.inventory());
    }

    /** True once something has been crafted. */
    public boolean processed() {
        return processed;
    }

    /** Plain items the player has already do not need crafting or fetching again. */
    private static List<ChainItem> prepareInitialItems(ChainMath math, Map<String, Long> inventory) {
        final List<ChainItem> initial = new ArrayList<>();

        for (ChainItem item : math.initialItems) {
            final long amount = Math.max(0, item.amount() - inventory.getOrDefault(item.item, 0L));
            if (amount > 0) {
                initial.add(item.copyWithMultiplier(amount));
            }
        }

        return initial;
    }

    public String next() {
        while (true) {
            switch (phase) {
                case PASS:
                    changed = false;
                    iterator = new ChainIterator(math, initialItems);
                    iterator.updateInventory(host.inventory());
                    phase = Phase.BATCH;
                    break;
                case BATCH:
                    if (!iterator.hasNext()) {
                        if (!changed) return null;
                        phase = Phase.PASS;
                        break;
                    }
                    batch = new ArrayList<>(
                        iterator.next()
                            .entrySet());
                    index = 0;
                    batchCrafted = false;
                    phase = Phase.ENTRY;
                    break;
                case ENTRY:
                    if (index >= batch.size()) {
                        if (batchCrafted) {
                            changed = true;
                            processed = true;
                            iterator.updateInventory(host.inventory());
                        }
                        phase = Phase.BATCH;
                        break;
                    }
                    current = batch.get(index)
                        .getKey();
                    remaining = batch.get(index)
                        .getValue();
                    entryCrafted = false;
                    phase = Phase.STEP;
                    break;
                case STEP:
                    if (remaining > 0 && host.canCraft(current)) return current;
                    batchCrafted |= entryCrafted;
                    index++;
                    phase = Phase.ENTRY;
                    break;
            }
        }
    }

    public void crafted() {
        remaining--;
        entryCrafted = true;
    }

    public void abandon() {
        remaining = 0;
    }
}
