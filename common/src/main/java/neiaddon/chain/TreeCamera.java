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

/** NEI's tree Camera: pan, zoom and which part of the tree is visible. */
public final class TreeCamera {

    private static final float ZOOM_MIN = 0.1f;
    private static final float ZOOM_MAX = 8.0f;
    private static final double ZOOM_STEP_BASE = 1.4;
    private static final float SCROLL_STEP_DIVISOR = 5f;
    private static final int CULL_MARGIN = 32;
    private static final int CULL_ROW_MARGIN = 16;
    private static final float INITIAL_SCROLL = -8f;

    public int widgetX;
    public int widgetY;
    public int widgetW;
    public int widgetH;

    public float scrollX = INITIAL_SCROLL;
    public float scrollY = INITIAL_SCROLL;
    public float zoomLevel = 1.0f;

    private float lastDragX;
    private float lastDragY;
    private boolean dragging;

    public void setViewport(int x, int y, int w, int h) {
        this.widgetX = x;
        this.widgetY = y;
        this.widgetW = w;
        this.widgetH = h;
    }

    public boolean isInWidget(double x, double y) {
        return x >= this.widgetX && y >= this.widgetY && x < this.widgetX + this.widgetW && y < this.widgetY + this.widgetH;
    }

    public void startDrag(double mouseX, double mouseY) {
        this.dragging = true;
        this.lastDragX = (float) mouseX;
        this.lastDragY = (float) mouseY;
    }

    public void drag(double mouseX, double mouseY) {
        if (!this.dragging) return;
        this.scrollX -= (mouseX - this.lastDragX) / this.zoomLevel;
        this.scrollY -= (mouseY - this.lastDragY) / this.zoomLevel;
        this.lastDragX = (float) mouseX;
        this.lastDragY = (float) mouseY;
    }

    public void endDrag() {
        this.dragging = false;
    }

    public boolean isDragging() {
        return this.dragging;
    }

    /** Wheel: Shift scrolls sideways, Ctrl zooms around the mouse, else up and down. */
    public void scroll(double mouseX, double mouseY, int direction, boolean shiftKey, boolean zoomKey) {
        if (shiftKey) {
            this.scrollX -= direction * this.widgetW / (SCROLL_STEP_DIVISOR * this.zoomLevel);
        } else if (zoomKey) {
            final double localX = mouseX - this.widgetX;
            final double localY = mouseY - this.widgetY;
            final float oldZoom = this.zoomLevel;

            this.zoomLevel = clamp((float) (this.zoomLevel * Math.pow(ZOOM_STEP_BASE, direction)), ZOOM_MIN, ZOOM_MAX);
            this.scrollX += localX / oldZoom - localX / this.zoomLevel;
            this.scrollY += localY / oldZoom - localY / this.zoomLevel;
        } else {
            this.scrollY -= direction * this.widgetH / (SCROLL_STEP_DIVISOR * this.zoomLevel);
        }
    }

    public void centerOn(float worldX, float worldY) {
        this.scrollX = worldX - (this.widgetW / this.zoomLevel) / 2f;
        this.scrollY = worldY - (this.widgetH / this.zoomLevel) / 2f;
    }

    public void clampToBounds(int treeWidth, int treeHeight) {
        this.scrollX = clamp(this.scrollX, -this.widgetW / this.zoomLevel + 4, treeWidth - 4);
        this.scrollY = clamp(this.scrollY, -this.widgetH / this.zoomLevel + 4, treeHeight - 4);
    }

    public int visibleRowMin() {
        final float zScrollY = this.scrollY * this.zoomLevel;
        return (int) ((zScrollY - CULL_MARGIN) / this.zoomLevel) - CULL_ROW_MARGIN;
    }

    public int visibleRowMax() {
        final float zScrollY = this.scrollY * this.zoomLevel;
        return (int) ((zScrollY + CULL_MARGIN) / this.zoomLevel) + (int) (this.widgetH / this.zoomLevel) + CULL_ROW_MARGIN;
    }

    /** Screen position -> tree coordinates. */
    public double[] toWorldPoint(double mouseX, double mouseY) {
        final float zScrollX = this.scrollX * this.zoomLevel;
        final float zScrollY = this.scrollY * this.zoomLevel;
        return new double[] { (mouseX - this.widgetX + zScrollX) / this.zoomLevel,
            (mouseY - this.widgetY + zScrollY) / this.zoomLevel };
    }

    public static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }
}
