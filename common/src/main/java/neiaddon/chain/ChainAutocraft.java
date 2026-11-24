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
 * Decides what to craft next. Call {@link #next()} for a recipe id to craft (once, or up to {@link #remaining()} times at
 * once), craft it (and let the inventory settle), call {@link #crafted(long)}, and ask again; {@code null} means finished. {@link #abandon()} gives up on the
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
    /**
     * #46 (round 16): the recipes the game side gave a turn back with {@link #defer()}. Nothing was
     * credited for them, so the chain still owes their crafts and a later pass hands them again; while this set is
     * not empty, {@link #next()} starts one more pass even when the last one crafted nothing, which is what keeps
     * a run from ending while a deferred recipe waits for input another recipe of the chain will craft.
     */
    private final java.util.LinkedHashSet<String> revisit = new java.util.LinkedHashSet<>();
    /** Every recipe the chain handed out in this run, in the order it was first handed out. */
    private final java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
    /**
     * Per recipe: the crafts the batches of the current pass asked of it, summed. Cleared when a pass starts. The run
     * ends after a pass that crafted nothing, so at the end this is exactly what the chain still wanted and did not get
     * (a recipe an earlier pass asked for but the last pass no longer needs is not in it).
     */
    private final java.util.HashMap<String, Long> passWanted = new java.util.HashMap<>();

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
                    passWanted.clear();
                    iterator = new ChainIterator(math, initialItems);
                    iterator.updateInventory(host.inventory());
                    phase = Phase.BATCH;
                    break;
                case BATCH:
                    if (!iterator.hasNext()) {
                        if (!changed) {
                            // #46 (round 16): a deferred visit is still owed its crafts: start one more
                            // pass even though nothing was crafted, and empty the set: only a visit deferred anew
                            // keeps the run going, so a recipe that stays refused ends the run as before
                            if (revisit.isEmpty()) return null;
                            revisit.clear();
                            phase = Phase.PASS;
                            break;
                        }
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
                    seen.add(current);
                    if (remaining > 0) passWanted.merge(current, remaining, Long::sum);
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
        crafted(1);
    }

    /** {@code n} crafts of the current recipe were done at once. */
    public void crafted(long n) {
        remaining = Math.max(0, remaining - n);
        entryCrafted = true;
    }

    /** How many more crafts of the recipe {@link #next()} returned are wanted. */
    public long remaining() {
        return remaining;
    }

    /**
     * After {@link #next()} returned null: per recipe, the crafts the chain still wanted and did not get, in the order
     * the recipes were first handed out (see {@link #passWanted}). Recipes that got everything are left out.
     */
    public java.util.Map<String, Long> stillWanted() {
        java.util.LinkedHashMap<String, Long> out = new java.util.LinkedHashMap<>();
        for (String recipeId : seen) {
            Long n = passWanted.get(recipeId);
            if (n != null && n > 0) out.put(recipeId, n);
        }
        return out;
    }

    public void abandon() {
        remaining = 0;
    }

    /**
     * #46 (round 16): like {@link #abandon()}, but the game side says the visit was refused over input
     * the chain itself plans to craft: the recipe is remembered in {@link #revisit} so its turn comes back later in
     * this same run. No crafts are credited, so a later pass hands the recipe again through the iterator like any
     * other recipe the chain still wants.
     */
    public void defer() {
        if (current != null && remaining > 0) revisit.add(current);
        remaining = 0;
    }
}
