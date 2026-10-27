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

/** One node of the crafting tree (NEI's ItemTreeSlot): an item some recipe needs, or a result at the top. */
public final class TreeSlot {

    public final ChainItem ingrItem;
    /** The item (and so recipe) that makes it; null for a raw material or something already in stock. */
    public final ChainItem prefItem;

    public final long ingeritRequestedAmount;
    public final long currentRequestedAmount;
    public final long requestedAmount;

    public final long containerAmount;
    public final long producedAmount;
    /** What still has to be made (0 = taken from stock / resolved). */
    public final long leftAmount;
    /** Runs of the producing recipe. */
    public final long multiplier;
    /** The item comes back (worn) after a craft, like a tool: its amount is only an estimate (NEI draws it with a ~). */
    public final boolean isContainerItem;

    public TreeSlot(ChainItem ingrItem, long ingeritRequestedAmount, long currentRequestedAmount, long producedAmount,
        long containerAmount, ChainItem prefItem) {
        this(ingrItem, ingeritRequestedAmount, currentRequestedAmount, producedAmount, containerAmount, prefItem, false);
    }

    public TreeSlot(ChainItem ingrItem, long ingeritRequestedAmount, long currentRequestedAmount, long producedAmount,
        long containerAmount, ChainItem prefItem, boolean isContainerItem) {
        this.isContainerItem = isContainerItem;
        this.ingrItem = ingrItem;
        this.ingeritRequestedAmount = ingeritRequestedAmount;
        this.currentRequestedAmount = currentRequestedAmount;
        this.requestedAmount = ingeritRequestedAmount + currentRequestedAmount;
        this.containerAmount = containerAmount;
        this.producedAmount = producedAmount;
        this.leftAmount = producedAmount + containerAmount;
        this.prefItem = prefItem;
        this.multiplier = (prefItem != null ? prefItem : ingrItem).multiplierFromAmount(this.requestedAmount);
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
