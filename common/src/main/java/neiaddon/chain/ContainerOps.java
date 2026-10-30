/*
 * Recipe chain maths for the NEI addon for JEI.
 *
 * The container item part of GTNH NotEnoughItems' codechicken.nei.recipe.chain.RecipeChainMath
 * (https://github.com/GTNewHorizons/NotEnoughItems), originally by Chicken-Bones, GTNewHorizons and the
 * NotEnoughItems contributors. Licensed under the GNU Lesser General Public License, version 3 or (at your option)
 * any later version.
 *
 * It uses no Minecraft classes: the game side (crafting remainders, item comparison) is behind this interface, so the
 * maths can be tested with a plain javac (see tools/ChainMathCheck.java).
 */
package neiaddon.chain;

/**
 * What the chain maths needs to know about items that stay behind when they are used in a recipe (a bucket, a damaged
 * tool). The stacks are opaque objects of the caller; {@code null} means "no stack".
 */
public interface ContainerOps {

    /** A stack still in the list of leftovers, with how many of it there are (NEI's ItemStack with a stackSize). */
    final class Held {

        public Object stack;
        public long count;

        public Held(Object stack, long count) {
            this.stack = stack;
            this.count = count;
        }
    }

    /** NEI's ContainerItemResult: what is left of a stack after some uses. */
    final class Use {

        /** The stack after the uses; null when it was used up or turned into another item. */
        public final Object stack;
        public final long leftSteps;
        /** The other item the stack turned into (a milk bucket into a bucket), else null. */
        public final Object containerItem;

        public Use(Object stack, long leftSteps, Object containerItem) {
            this.stack = stack;
            this.leftSteps = leftSteps;
            this.containerItem = containerItem;
        }
    }

    /** One item of the stack the chain item stands for, or null when it is not a real item. */
    Object stackOf(ChainItem item);

    /** One item of another item the slot accepts, or null. */
    Object stackOf(String itemId);

    /** The registry id of a stack. */
    String idOf(Object stack);

    /** NEI's {@code item.hasContainerItem(stack)}: does using the stack leave something behind? */
    boolean hasContainerItem(Object stack);

    /** NEI's areStacksSameTypeCraftingWithNBT. */
    boolean sameForCrafting(Object a, Object b);

    /** Same item (NEI compares {@code item != aStack.getItem()}). */
    boolean sameItem(Object a, Object b);

    /** NEI's {@code item.getContainerItem(stack)}: what is left after one use, or null. */
    Object remainder(Object stack);

    Object copy(Object stack);
}
