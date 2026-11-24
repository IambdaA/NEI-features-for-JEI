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
import java.util.List;

/**
 * One node of the crafting tree (NEI's ItemTreeSlot): an item some recipe needs, or a result at the top. A recipe
 * with several results is one node (#42: it used to be one node per wanted result): every result the
 * recipe makes is one of its {@link #outputs}, even one nothing downstream needs; the node's own amounts are the sum.
 */
public final class TreeSlot {

    /** One result of the node's recipe: what the maths asked for and what the recipe ran. */
    public static final class Output {

        public final ChainItem ingrItem;
        /** The result item of the recipe that makes it; null when the node is an unmade raw material. */
        public final ChainItem prefItem;

        public final long ingeritRequestedAmount;
        public final long currentRequestedAmount;
        public final long requestedAmount;

        public final long containerAmount;
        public final long producedAmount;
        /** What still has to be made (0 = taken from stock / resolved). */
        public final long leftAmount;
        /** The item comes back (worn) after a craft, like a tool: its amount is only an estimate. */
        public final boolean isContainerItem;

        private Output(ChainItem ingrItem, ChainItem prefItem, long ingeritRequestedAmount, long currentRequestedAmount,
            long producedAmount, long containerAmount, boolean isContainerItem) {
            this.ingrItem = ingrItem;
            this.prefItem = prefItem;
            this.ingeritRequestedAmount = ingeritRequestedAmount;
            this.currentRequestedAmount = currentRequestedAmount;
            this.requestedAmount = ingeritRequestedAmount + currentRequestedAmount;
            this.containerAmount = containerAmount;
            this.producedAmount = producedAmount;
            this.leftAmount = producedAmount + containerAmount;
            this.isContainerItem = isContainerItem;
        }

        /** Runs of this output's share of the recipe. */
        public long multiplier() {
            return (prefItem != null ? prefItem : ingrItem).multiplierFromAmount(requestedAmount);
        }

        /** The item that is drawn: what makes it, else what was asked for. */
        public ChainItem shown() {
            return prefItem != null ? prefItem : ingrItem;
        }
    }

    public final ChainItem ingrItem;
    /** The item (and so recipe) that makes it; null for a raw material or something already in stock. */
    public final ChainItem prefItem;

    public long ingeritRequestedAmount;
    public long currentRequestedAmount;
    public long requestedAmount;

    public long containerAmount;
    public long producedAmount;
    /** What still has to be made (0 = taken from stock / resolved). */
    public long leftAmount;
    /** Runs of the producing recipe (the most of any of its outputs). */
    public long multiplier;
    /** The item comes back (worn) after a craft, like a tool: its amount is only an estimate (NEI draws it with a ~). */
    public boolean isContainerItem;

    /** The recipe's results shown at this node, the wanted ones first, in the order the maths asked for them. */
    public final List<Output> outputs;

    public TreeSlot(ChainItem ingrItem, long ingeritRequestedAmount, long currentRequestedAmount, long producedAmount,
        long containerAmount, ChainItem prefItem) {
        this(ingrItem, ingeritRequestedAmount, currentRequestedAmount, producedAmount, containerAmount, prefItem, false);
    }

    public TreeSlot(ChainItem ingrItem, long ingeritRequestedAmount, long currentRequestedAmount, long producedAmount,
        long containerAmount, ChainItem prefItem, boolean isContainerItem) {
        this.isContainerItem = isContainerItem;
        this.ingrItem = ingrItem;
        this.prefItem = prefItem;
        this.outputs = new ArrayList<>();
        this.outputs.add(new Output(ingrItem, prefItem, ingeritRequestedAmount, currentRequestedAmount, producedAmount,
            containerAmount, isContainerItem));
        recalculate();
    }

    /**
     * Adds one more of this recipe's results to the node (or merges into the entry for it the maths asked for
     * already). Only {@link TreeMath} calls this while building the tree.
     */
    void addOutput(ChainItem ingrItem, ChainItem prefItem, long ingeritRequestedAmount, long currentRequestedAmount,
        long producedAmount, long containerAmount, boolean isContainerItem) {
        for (int i = 0; i < this.outputs.size(); i++) {
            final Output output = this.outputs.get(i);
            if (prefItem != null ? prefItem.equals(output.prefItem)
                : output.prefItem == null && ingrItem.equals(output.ingrItem)) {
                this.outputs.set(i, new Output(output.ingrItem, output.prefItem,
                    output.ingeritRequestedAmount + ingeritRequestedAmount,
                    output.currentRequestedAmount + currentRequestedAmount,
                    output.producedAmount + producedAmount, output.containerAmount + containerAmount,
                    output.isContainerItem || isContainerItem));
                recalculate();
                return;
            }
        }
        this.outputs.add(new Output(ingrItem, prefItem, ingeritRequestedAmount, currentRequestedAmount, producedAmount,
            containerAmount, isContainerItem));
        recalculate();
    }

    /**
     * Issue #42 follow-up: puts the recipe's chosen result (the one the player entered the recipe on, given by
     * {@link TreeMath}) in the node's leftmost slot; the others keep their order. Only {@link TreeMath} calls this, on a
     * root node it has just made: all its outputs are unflagged there, so the node's own container flag (taken from the
     * first output in {@link #recalculate()}) does not change.
     */
    void moveOutputFirst(String itemId) {
        for (int i = 1; i < this.outputs.size(); i++) {
            final Output output = this.outputs.get(i);
            if (itemId.equals(output.shown().item)) {
                this.outputs.add(0, this.outputs.remove(i));
                recalculate();
                return;
            }
        }
    }

    /** The node's own amounts are the sum of its outputs; kept current while TreeMath merges into the node. */
    private void recalculate() {
        long multiplier = 0;
        boolean container = false;
        this.ingeritRequestedAmount = 0;
        this.currentRequestedAmount = 0;
        this.containerAmount = 0;
        this.producedAmount = 0;
        for (Output output : this.outputs) {
            this.ingeritRequestedAmount += output.ingeritRequestedAmount;
            this.currentRequestedAmount += output.currentRequestedAmount;
            this.containerAmount += output.containerAmount;
            this.producedAmount += output.producedAmount;
            multiplier = Math.max(multiplier, output.multiplier());
            container = container || output.isContainerItem;
        }
        this.requestedAmount = this.ingeritRequestedAmount + this.currentRequestedAmount;
        this.leftAmount = this.producedAmount + this.containerAmount;
        this.multiplier = multiplier;
        // The node is a container when its first result is one, or it already was one and some result is one
        // (the flag is set before the first Output is built, so a true flag always shows up in `container` too).
        boolean wasContainerItem = this.isContainerItem;
        this.isContainerItem = this.outputs.get(0).isContainerItem || (container && wasContainerItem);
    }

    /** Whether the node already shows this result of its recipe (issue #42: an output nothing needs still shows). */
    boolean hasOutput(ChainItem item) {
        for (Output output : this.outputs) {
            if (item.equals(output.ingrItem) || item.equals(output.prefItem)) {
                return true;
            }
        }
        return false;
    }

    /** The node's results, never empty; the first one is the item the node is keyed by. */
    public List<Output> outputs() {
        return Collections.unmodifiableList(this.outputs);
    }

    /** The item that is drawn: what makes it, else what was asked for. */
    public ChainItem shown() {
        return prefItem != null ? prefItem : ingrItem;
    }

    /** The recipe that makes this item, or null. */
    public String recipeId() {
        return prefItem != null ? prefItem.recipeId : null;
    }
}
