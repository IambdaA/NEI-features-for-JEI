/*
 * Recipe chain maths for the NEI addon for JEI.
 *
 * The counting rules of the game side's autocrafting (#46): how many crafts one shift-click on a result
 * slot is credited with, and when a recipe is given up on after batch clicks that crafted nothing. They are kept here,
 * without Minecraft classes, so tools/AutoCraftCheck.java tests the rules AutoCraft really uses.
 */
package neiaddon.chain;

/**
 * The pure counting rules behind {@code AutoCraft}. Every value is a count of crafts (or of items in one grid slot).
 * {@link Long#MAX_VALUE} is the "nothing measurable" value: a grid of nothing but tools (a tool is not used up).
 */
public final class CraftCount {

    /**
     * #46: per recipe, how often in a row a batch shift-click crafted nothing on the server before the recipe
     * is given up on instead of retried forever (see {@link #giveUp(int)}).
     */
    public static final int BATCH_ZERO_TRIES = 2;

    private CraftCount() {}

    /**
     * The count of one single-set shift-click from its measured drop: never below 1, because one set was asked for and a
     * grid of nothing but tools cannot be measured.
     */
    public static long single(long measured) {
        return measured == Long.MAX_VALUE || measured < 1 ? 1 : measured;
    }

    /**
     * #46: the count of a batch shift-click from its measured drop, without the floor at 1. A click that
     * crafted nothing counts 0; a grid of nothing but tools cannot be measured, so there the one-craft guess stays.
     */
    public static long batch(long measured) {
        return measured == Long.MAX_VALUE ? 1 : Math.max(0, measured);
    }

    /**
     * How many items one grid slot lost: {@code wasCount} before the click, and after it {@code nowCount} if the slot still
     * holds the same item, else nothing ({@code sameItem} false: a bucket was left behind or the slot emptied).
     */
    public static long slotDrop(long wasCount, boolean sameItem, long nowCount) {
        return wasCount - (sameItem ? nowCount : 0);
    }

    /**
     * #46: whether a recipe is given up on after {@code zeroTries} batch clicks in a row that crafted nothing
     * (the count including the latest one).
     */
    public static boolean giveUp(int zeroTries) {
        return zeroTries >= BATCH_ZERO_TRIES;
    }
}
