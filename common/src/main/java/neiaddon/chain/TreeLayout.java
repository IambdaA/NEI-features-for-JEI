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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** NEI's CraftingTreeCanvas layout: where every node of the tree sits (a tidy tree, parents centred over children). */
public final class TreeLayout {

    public static final int SLOT_SIZE = 18;
    public static final int X_SPACING = 24;
    public static final int Y_SPACING = 14;
    public static final int ROOT_SPACING = 36;

    public static final class Point {

        public int x;
        public int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public final TreeGraph graph = new TreeGraph();
    public final Map<TreeSlot, Point> pointByNode = new HashMap<>();
    public final TreeMap<Integer, List<TreeSlot>> rows = new TreeMap<>();
    public final Set<String> collapsedRecipes = new HashSet<>();
    public int treeWidth = 0;
    public int treeHeight = 0;

    /**
     * Lays the tree out. Returns how far {@code anchor} moved (so the view can follow it), or null without an anchor.
     */
    public int[] recalculate(Set<String> collapsedRecipes, Set<ChainItem> collapsedItems, TreeSlot anchor) {
        final Map<TreeSlot, Integer> offsetFromParent = new HashMap<>();
        final Point anchorOld = anchor != null ? resolveAnchorPoint(anchor) : null;
        final int anchorOldX = anchorOld != null ? anchorOld.x : 0;
        final int anchorOldY = anchorOld != null ? anchorOld.y : 0;

        this.collapsedRecipes.clear();
        this.pointByNode.clear();
        this.rows.clear();
        this.treeWidth = 0;
        this.treeHeight = 0;
        this.collapsedRecipes.addAll(collapsedRecipes);

        for (TreeSlot node : this.graph.items) {
            this.pointByNode.put(node, new Point(0, 0));
        }

        final List<int[]> contour = new ArrayList<>();

        for (TreeSlot root : this.graph.roots) {
            final List<int[]> layout = computeLayout(root, collapsedItems, offsetFromParent);
            final int shift = contour.isEmpty() ? 0 : requiredRootShift(contour, layout);

            mergeContour(contour, layout, shift);
            assignPositions(root, shift, 0, collapsedItems, offsetFromParent);
        }

        int minX = 0;

        for (Point point : this.pointByNode.values()) {
            minX = Math.min(minX, point.x);
        }

        if (minX < 0) {
            for (Point point : this.pointByNode.values()) {
                point.x -= minX;
            }
        }

        for (List<TreeSlot> row : this.rows.values()) {
            for (TreeSlot node : row) {
                this.treeWidth = Math.max(this.treeWidth, this.pointByNode.get(node).x + SLOT_SIZE);
                this.treeHeight = Math.max(this.treeHeight, this.pointByNode.get(node).y + SLOT_SIZE);
            }
        }

        if (anchorOld != null) {
            final Point anchorNew = resolveAnchorPoint(anchor);

            if (anchorNew != null) {
                return new int[] { anchorNew.x - anchorOldX, anchorNew.y - anchorOldY };
            }
        }

        return null;
    }

    private Point resolveAnchorPoint(TreeSlot anchor) {
        Point result = this.pointByNode.get(anchor);

        if (result == null) {
            for (Map.Entry<TreeSlot, Point> entry : this.pointByNode.entrySet()) {
                if (entry.getKey().ingrItem.equals(anchor.ingrItem)) {
                    return entry.getValue();
                }
            }
        }

        return result;
    }

    private List<int[]> computeLayout(TreeSlot node, Set<ChainItem> collapsedItems,
        Map<TreeSlot, Integer> offsetFromParent) {
        List<TreeSlot> children = Collections.emptyList();

        if (!collapsedItems.contains(node.ingrItem)) {
            children = this.graph.childrenOf.getOrDefault(node, Collections.emptyList());
        }

        if (children.isEmpty()) {
            final List<int[]> rows = new ArrayList<>();
            rows.add(new int[] { 0, 0 });
            return rows;
        }

        final int[] offsets = new int[children.size()];
        final List<int[]> combined = new ArrayList<>();

        for (int i = 0; i < children.size(); i++) {
            final List<int[]> childRows = computeLayout(children.get(i), collapsedItems, offsetFromParent);
            final int shift = i == 0 ? 0 : requiredShift(combined, childRows, X_SPACING);

            offsets[i] = shift;
            mergeContour(combined, childRows, shift);
        }

        final int selfX = (offsets[0] + offsets[children.size() - 1]) / 2;

        for (int i = 0; i < children.size(); i++) {
            offsetFromParent.put(children.get(i), offsets[i] - selfX);
        }

        for (int[] row : combined) {
            row[0] -= selfX;
            row[1] -= selfX;
        }

        final List<int[]> rows = new ArrayList<>(combined.size() + 1);
        rows.add(new int[] { 0, 0 });
        rows.addAll(combined);

        return rows;
    }

    private static int requiredShift(List<int[]> existing, List<int[]> incoming, int spacing) {
        int shift = 0;
        final int rows = Math.min(existing.size(), incoming.size());

        for (int r = 0; r < rows; r++) {
            shift = Math.max(shift, existing.get(r)[1] + spacing - incoming.get(r)[0]);
        }

        return shift;
    }

    private static int requiredRootShift(List<int[]> existing, List<int[]> incoming) {
        int existingRight = Integer.MIN_VALUE;

        for (int[] row : existing) {
            existingRight = Math.max(existingRight, row[1]);
        }

        int incomingLeft = Integer.MAX_VALUE;

        for (int[] row : incoming) {
            incomingLeft = Math.min(incomingLeft, row[0]);
        }

        return Math.max(0, existingRight + ROOT_SPACING - incomingLeft);
    }

    private static void mergeContour(List<int[]> target, List<int[]> source, int shift) {
        for (int r = 0; r < source.size(); r++) {
            final int left = source.get(r)[0] + shift;
            final int right = source.get(r)[1] + shift;

            if (r < target.size()) {
                target.get(r)[0] = Math.min(target.get(r)[0], left);
                target.get(r)[1] = Math.max(target.get(r)[1], right);
            } else {
                target.add(new int[] { left, right });
            }
        }
    }

    private void assignPositions(TreeSlot node, int x, int y, Set<ChainItem> collapsedItems,
        Map<TreeSlot, Integer> offsetFromParent) {
        this.rows.computeIfAbsent(y, ignored -> new ArrayList<>())
            .add(node);
        this.pointByNode.put(node, new Point(x, y));

        final int childY = y + SLOT_SIZE + Y_SPACING;

        if (!collapsedItems.contains(node.ingrItem)) {
            for (TreeSlot child : this.graph.childrenOf.getOrDefault(node, Collections.emptyList())) {
                assignPositions(child, x + offsetFromParent.get(child), childY, collapsedItems, offsetFromParent);
            }
        }
    }
}
