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
import java.util.List;
import java.util.function.ToIntFunction;

/** NEI's StatsPanel.layoutSections: where the labels and slots of the stats sections go. */
public final class TreeStatsLayout {

    public static final int SLOT = 18;
    public static final int INLINE_GAP = 2;
    public static final int BLOCK_GAP = 2;
    public static final int COLUMN_GAP = 10;
    public static final int PADDING_BLOCK = 4;
    public static final int FONT_HEIGHT = 9;

    /** A label ({@code index == -1}) or the {@code index}th slot of a section, at panel-content coordinates. */
    public record Placed(int section, int index, int x, int y) {}

    public final List<Placed> placed = new ArrayList<>();
    public int contentWidth;
    public int contentHeight;
    /** The panel's height including its padding. */
    public int panelHeight;
    /** Content is centred when it is narrower than the panel. */
    public int shiftX;

    public static TreeStatsLayout compute(List<String> labels, List<Integer> sizes, int widgetW, int widgetH,
        ToIntFunction<String> textWidth) {
        final TreeStatsLayout out = new TreeStatsLayout();
        final int sectionCount = sizes.size();
        final int maxRowWidth = Math.max(SLOT, widgetW);
        final int[] labelWidths = new int[sectionCount];
        final int[] columns = new int[sectionCount];
        final int maxRows = widgetH / ((FONT_HEIGHT + 4) + SLOT * 2 + BLOCK_GAP) > 3 ? 2 : 1;

        for (int i = 0; i < sectionCount; i++) {
            labelWidths[i] = textWidth.applyAsInt(labels.get(i) + ":");
            columns[i] = sizes.get(i) == 0 ? 0 : (sizes.get(i) + maxRows - 1) / maxRows;
        }

        int compactWidth = COLUMN_GAP * Math.max(0, sectionCount - 1);

        for (int i = 0; i < sectionCount; i++) {
            compactWidth += sectionWidth(columns[i], labelWidths[i]);
        }

        if (compactWidth <= maxRowWidth) {
            int budget = (maxRowWidth - compactWidth) / (SLOT + INLINE_GAP);

            while (budget > 0) {
                boolean grew = false;

                for (int i = 0; i < sectionCount && budget > 0; i++) {
                    if (columns[i] < sizes.get(i)) {
                        columns[i]++;
                        --budget;
                        grew = true;
                    }
                }

                if (!grew) break;
            }
        }

        int cursorX = 0;
        int maxHeight = 0;

        for (int i = 0; i < sectionCount; i++) {
            final int height = out.placeSection(i, sizes.get(i), columns[i], cursorX, 0);
            maxHeight = Math.max(maxHeight, height);
            cursorX += sectionWidth(columns[i], labelWidths[i]) + COLUMN_GAP;
        }

        out.contentWidth = Math.max(0, cursorX - COLUMN_GAP);
        out.contentHeight = maxHeight;

        final int maxVisibleH = maxRows * SLOT + Math.max(0, maxRows - 1) * BLOCK_GAP + FONT_HEIGHT + 4;
        out.panelHeight = Math.min(out.contentHeight, maxVisibleH) + PADDING_BLOCK * 2;
        out.shiftX = out.contentWidth < widgetW ? (widgetW - out.contentWidth) / 2 : 0;
        return out;
    }

    private static int sectionWidth(int columns, int labelWidth) {
        return Math.max(columns * SLOT + Math.max(0, columns - 1) * INLINE_GAP, labelWidth);
    }

    private int placeSection(int section, int count, int columns, int x, int y) {
        placed.add(new Placed(section, -1, x, y));

        final int gridY = y + FONT_HEIGHT + 4;
        final int rowWidth = columns * SLOT + Math.max(0, columns - 1) * INLINE_GAP;
        int cursorX = 0;
        int row = 0;
        boolean any = false;

        for (int i = 0; i < count; i++) {
            if (cursorX > 0) {
                if (cursorX + INLINE_GAP + SLOT > rowWidth) {
                    cursorX = 0;
                    row++;
                } else {
                    cursorX += INLINE_GAP;
                }
            } else if (cursorX + SLOT > rowWidth) {
                cursorX = 0;
                row++;
            }

            placed.add(new Placed(section, i, x + cursorX, gridY + row * (SLOT + BLOCK_GAP)));
            cursorX += SLOT;
            any = true;
        }

        final int rows = any ? row + 1 : 0;
        return (gridY - y) + rows * SLOT + Math.max(0, rows - 1) * BLOCK_GAP;
    }
}
