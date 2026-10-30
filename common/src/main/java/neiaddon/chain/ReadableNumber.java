/*
 * Crafting tree maths for the NEI addon for JEI.
 *
 * Port of GTNH NotEnoughItems' codechicken.nei.util.ReadableNumberConverter (itself copied from Applied Energistics 2),
 * https://github.com/GTNewHorizons/NotEnoughItems. Licensed under the GNU Lesser General Public License, version 3 or
 * (at your option) any later version.
 *
 * Uses no Minecraft classes so it can be tested with a plain javac (see tools/TreeCheck.java).
 */
package neiaddon.chain;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Big numbers in SI form ("12k", "1.5M") restricted to a width, like NEI's amounts in the tree. */
public final class ReadableNumber {

    private static final int DIVISION_BASE = 1000;
    private static final char[] POSTFIXES = "kMGTPE".toCharArray();

    private ReadableNumber() {}

    private static DecimalFormat format() {
        final DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setDecimalSeparator('.');
        symbols.setGroupingSeparator(',');
        final DecimalFormat format = new DecimalFormat(".#;0.#");
        format.setDecimalFormatSymbols(symbols);
        format.setRoundingMode(RoundingMode.DOWN);
        return format;
    }

    private static String restricted(long number, int width) {
        final String numberString = Long.toString(number);
        int numberSize = numberString.length();
        if (numberSize <= width) return numberString;

        long base = number;
        double last = base * 1000;
        int exponent = -1;
        String postFix = "";

        while (numberSize > width) {
            last = base;
            base /= DIVISION_BASE;
            exponent++;
            numberSize = Long.toString(base).length() + 1;
            postFix = String.valueOf(POSTFIXES[exponent]);
        }

        final String withPrecision = format().format(last / DIVISION_BASE) + postFix;
        final String withoutPrecision = Long.toString(base) + postFix;
        return withPrecision.length() <= width ? withPrecision : withoutPrecision;
    }

    /** NEI's toWideReadableForm: at most 4 characters. */
    public static String wide(long number) {
        return restricted(Math.max(0, number), 4);
    }

    /** What the tree draws on a slot: the plain number below 10 000, else the wide form. */
    public static String amount(long amount) {
        return amount < 10_000 ? String.valueOf(amount) : wide(amount);
    }
}
