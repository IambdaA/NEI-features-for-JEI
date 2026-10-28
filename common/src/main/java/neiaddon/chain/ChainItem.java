/*
 * Recipe chain maths for the NEI addon for JEI.
 *
 * This file is a port of GTNH NotEnoughItems' codechicken.nei.bookmark.BookmarkItem
 * (https://github.com/GTNewHorizons/NotEnoughItems), originally by Chicken-Bones, GTNewHorizons and
 * the NotEnoughItems contributors. Licensed under the GNU Lesser General Public License, version 3 or
 * (at your option) any later version.
 *
 * It uses no Minecraft classes so it can be tested with a plain javac (see tools/ChainMathCheck.java).
 */
package neiaddon.chain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One bookmarked item as the chain maths sees it.
 *
 * <p>
 * {@link #item} is an opaque key (the registry id). {@link #recipeId} is {@code null} for plain items.
 * {@link #factor} is how many of the item one run of the recipe makes (results) or uses (ingredients), and
 * {@code amount = factor * multiplier}. {@link #options} holds every item the slot accepts (tags), including
 * {@link #item} itself. {@link #payload} is the caller's own object; copies made by the maths keep it so the caller
 * can map the calculated values back.
 */
public final class ChainItem {

    public enum Type {

        /** A plain bookmarked item: what the player already has. */
        ITEM,
        /** A recipe result. */
        RESULT,
        /** A recipe ingredient. */
        INGREDIENT;

        public static Type fromInt(int type) {
            return type == 0 ? ITEM : type == 1 ? RESULT : INGREDIENT;
        }

        public int toInt() {
            return this == ITEM ? 0 : this == RESULT ? 1 : 2;
        }
    }

    public final String item;
    public final String recipeId;
    public final Type type;
    public final long factor;
    public final Set<String> options;
    public final Object payload;
    /**
     * Where the item came from, part of its identity like NEI's group id: 0 for the bookmarks, -1 for the player's
     * inventory and -2 for a container's storage when pulling items.
     */
    public final int source;
    public long multiplier;

    public ChainItem(String item, String recipeId, Type type, long factor, long multiplier, Set<String> options,
        Object payload) {
        this(item, recipeId, type, factor, multiplier, options, payload, 0);
    }

    public ChainItem(String item, String recipeId, Type type, long factor, long multiplier, Set<String> options,
        Object payload, int source) {
        this.source = source;
        this.item = item;
        this.recipeId = recipeId;
        this.type = type == null ? Type.ITEM : type;
        this.factor = factor;
        this.multiplier = multiplier;
        Set<String> opts = new LinkedHashSet<>();
        opts.add(item);
        if (options != null) opts.addAll(options);
        this.options = Collections.unmodifiableSet(opts);
        this.payload = payload;
    }

    public ChainItem copyWithMultiplier(long multiplier) {
        return new ChainItem(item, recipeId, type, factor, multiplier, options, payload, source);
    }

    public ChainItem withSource(int source) {
        return new ChainItem(item, recipeId, type, factor, multiplier, options, payload, source);
    }

    public boolean containsItems(ChainItem other) {
        for (String o : options) {
            if (other.options.contains(o)) return true;
        }
        return false;
    }

    public boolean emptyFactor() {
        return factor <= 0;
    }

    public long amount() {
        return amount(multiplier);
    }

    public long amount(long multiplier) {
        return saturatedMultiply(factor, multiplier);
    }

    /** Runs of the recipe needed to reach {@code amount} (rounded up). */
    public long multiplierFromAmount(long amount) {
        if (factor <= 0) return 0;
        return (amount + factor - 1) / factor;
    }

    static long saturatedMultiply(long a, long b) {
        long hi = Math.multiplyHigh(a, b);
        long lo = a * b;
        if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) return lo;
        return (hi < 0) == (lo < 0) ? Long.MAX_VALUE : (hi < 0 ? Long.MIN_VALUE : Long.MAX_VALUE);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, type.toInt(), recipeId, item);
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof ChainItem other) {
            return source == other.source && type == other.type && item.equals(other.item) && Objects.equals(recipeId, other.recipeId);
        }
        return false;
    }

    @Override
    public String toString() {
        return type + " " + item + " x" + factor + " *" + multiplier + (recipeId != null ? " @" + recipeId : "");
    }
}
