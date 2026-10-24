package neiaddon;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3x2fStack;

/**
 * Drawing helpers of the crafting tree window: pieces of NEI's {@code craftingtree.png}, NEI's small overlay text and a
 * tooltip box with icon lists. Every blit is 1:1 (same size on the texture and on the screen); bigger areas are tiled
 * like NEI's {@code DrawableResource}.
 */
final class TreeDraw {

    static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(NeiAddon.MODID, "textures/gui/craftingtree.png");

    /** A piece of the texture: where it is and how big. */
    record Sprite(int u, int v, int w, int h) {}

    // tree slots (row v=2) and stats slots (row v=26)
    static final Sprite SLOT_DEFAULT = new Sprite(68, 2, 18, 18);
    static final Sprite SLOT_MISSING = new Sprite(92, 2, 18, 18);
    static final Sprite SLOT_CRAFTING = new Sprite(116, 2, 18, 18);
    static final Sprite SLOT_DISABLED = new Sprite(140, 2, 18, 18);
    static final Sprite SLOT_HIGHLIGHTED = new Sprite(164, 2, 18, 18);
    static final int STATS_ROW = 24;

    static final Sprite ICON_CRAFTING = new Sprite(67, 48, 16, 16);
    static final Sprite ICON_REMOVE = new Sprite(85, 48, 16, 16);
    static final Sprite ICON_COLLAPSED = new Sprite(4, 88, 5, 5);
    static final Sprite ICON_COLLAPSED_HOVER = new Sprite(11, 88, 5, 5);
    static final Sprite ICON_EXPANDED = new Sprite(4, 95, 5, 5);
    static final Sprite ICON_EXPANDED_HOVER = new Sprite(11, 95, 5, 5);
    static final Sprite SCROLLBAR_H = new Sprite(0, 88, 2, 24);
    static final Sprite SCROLLBAR_W = new Sprite(0, 84, 24, 2);
    static final Sprite STATS_THUMB = new Sprite(26, 100, 24, 4);
    static final Sprite STATS_TRACK = new Sprite(26, 104, 24, 4);

    private TreeDraw() {}

    /** The same piece one row down (the stats panel uses the slot row at v=26). */
    static Sprite stats(Sprite tree) {
        return new Sprite(tree.u(), tree.v() + STATS_ROW, tree.w(), tree.h());
    }

    static void draw(GuiGraphicsExtractor g, Sprite s, int x, int y) {
        part(g, s.u(), s.v(), s.w(), s.h(), x, y);
    }

    /** A rectangle of the texture drawn 1:1. */
    static void part(GuiGraphicsExtractor g, int u, int v, int w, int h, int x, int y) {
        if (w <= 0 || h <= 0) return;
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, (float) u, (float) v, w, h, 256, 256);
    }

    /** NEI's {@code DrawableResource.draw(x, y, w, h)} with slices: corners as they are, edges and middle tiled. */
    static void sliced(GuiGraphicsExtractor g, Sprite s, int sliceLeft, int sliceRight, int sliceTop, int sliceBottom, int x, int y,
        int width, int height) {
        int middleW = s.w() - sliceLeft - sliceRight;
        int middleH = s.h() - sliceTop - sliceBottom;
        int tileW = width - sliceLeft - sliceRight;
        int tileH = height - sliceTop - sliceBottom;
        if (middleW <= 0 || middleH <= 0 || tileW <= 0 || tileH <= 0) {
            draw(g, s, x, y);
            return;
        }
        int countW = tileW / middleW;
        int remW = tileW - countW * middleW;
        int countH = tileH / middleH;
        int remH = tileH - countH * middleH;

        for (int tw = 0; tw <= countW; tw++) {
            int w = tw == countW ? remW : middleW;
            int px = x + sliceLeft + tw * middleW;
            for (int th = 0; th <= countH; th++) {
                int h = th == countH ? remH : middleH;
                part(g, s.u() + sliceLeft, s.v() + sliceTop, w, h, px, y + sliceTop + th * middleH);
            }
            if (sliceTop > 0) part(g, s.u() + sliceLeft, s.v(), w, sliceTop, px, y);
            if (sliceBottom > 0) part(g, s.u() + sliceLeft, s.v() + sliceTop + middleH, w, sliceBottom, px, y + sliceTop + tileH);
        }
        for (int th = 0; th <= countH; th++) {
            int h = th == countH ? remH : middleH;
            int py = y + sliceTop + th * middleH;
            if (sliceLeft > 0) part(g, s.u(), s.v() + sliceTop, sliceLeft, h, x, py);
            if (sliceRight > 0) part(g, s.u() + sliceLeft + middleW, s.v() + sliceTop, sliceRight, h, x + sliceLeft + tileW, py);
        }
        if (sliceLeft > 0 && sliceTop > 0) part(g, s.u(), s.v(), sliceLeft, sliceTop, x, y);
        if (sliceRight > 0 && sliceTop > 0) part(g, s.u() + s.w() - sliceRight, s.v(), sliceRight, sliceTop, x + sliceLeft + tileW, y);
        if (sliceLeft > 0 && sliceBottom > 0) part(g, s.u(), s.v() + s.h() - sliceBottom, sliceLeft, sliceBottom, x, y + sliceTop + tileH);
        if (sliceRight > 0 && sliceBottom > 0) {
            part(g, s.u() + s.w() - sliceRight, s.v() + s.h() - sliceBottom, sliceRight, sliceBottom, x + sliceLeft + tileW, y + sliceTop + tileH);
        }
    }

    /** A sprite tiled sideways over {@code width} pixels (the stats scrollbar's track and thumb). */
    static void tiledWide(GuiGraphicsExtractor g, Sprite s, int x, int y, int width) {
        for (int done = 0; done < width; done += s.w()) {
            part(g, s.u(), s.v(), Math.min(s.w(), width - done), s.h(), x + done, y);
        }
    }

    // ---------------------------------------------------------------- text

    enum Align {
        TOP_LEFT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    /**
     * NEI's {@code drawNEIOverlayText}: text at half size inside a rectangle (an amount in the corner of a slot's item).
     */
    static void overlayText(GuiGraphicsExtractor g, Font font, String text, int rx, int ry, int rw, int rh, int color, Align align) {
        float scale = 0.5f;
        float width = font.width(text) * scale;
        float x = align == Align.BOTTOM_RIGHT ? rx + rw - width : rx;
        float y = align == Align.TOP_LEFT ? ry : ry + rh - 9 * scale;
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate((float) Math.ceil(x), (float) Math.ceil(y));
        pose.scale(scale, scale);
        g.text(font, text, 0, 0, 0xFF000000 | color, true);
        pose.popMatrix();
    }

    // ---------------------------------------------------------------- tooltip

    /** An icon list of a tooltip: a label, then the items with their amounts (NEI's ItemsTooltipLineHandler). */
    record IconLine(String label, List<ItemStack> stacks, List<String> amounts, int active) {}

    static final int TIP_SLOT = 18;
    private static final int TIP_MAX_COLUMNS = 8;

    /** Width of an icon list drawn in a tooltip. */
    private static int iconWidth(IconLine line) {
        return Math.min(line.stacks().size(), TIP_MAX_COLUMNS) * TIP_SLOT;
    }

    private static int iconRows(IconLine line) {
        return (line.stacks().size() + TIP_MAX_COLUMNS - 1) / TIP_MAX_COLUMNS;
    }

    /**
     * Draws a tooltip box at the mouse: text lines, then icon lists, then a free area (a recipe picture) of
     * {@code extraW x extraH} that {@code extra} paints at its top left corner. The first line is the title and gets a
     * little more room, like every tooltip.
     */
    static void tooltip(GuiGraphicsExtractor g, Font font, int mx, int my, int screenW, int screenH, List<FormattedCharSequence> lines,
        List<IconLine> icons, int extraW, int extraH, ExtraPainter extra) {
        int textW = 0;
        for (FormattedCharSequence line : lines) textW = Math.max(textW, font.width(line));
        int width = textW;
        for (IconLine icon : icons) {
            width = Math.max(width, Math.max(iconWidth(icon), font.width(seq(icon.label() + ":"))));
        }
        width = Math.max(width, extraW);

        int height = lines.isEmpty() ? 0 : 10 * lines.size() + (lines.size() > 1 ? 2 : 0);
        for (IconLine icon : icons) height += 2 + 11 + iconRows(icon) * TIP_SLOT;
        if (extraH > 0) height += 2 + extraH;

        int x = mx + 12;
        int y = my - 12;
        if (x + width + 6 > screenW) x = mx - 12 - width;
        if (y + height + 6 > screenH) y = screenH - height - 6;
        y = Math.max(y, 6);
        x = Math.max(x, 6);

        g.nextStratum();
        g.fill(x - 3, y - 4, x + width + 3, y + height + 4, 0xF0100010);
        g.fill(x - 4, y - 3, x + width + 4, y + height + 3, 0xF0100010);
        g.fill(x - 3, y - 3, x + width + 3, y - 2, 0xF0280070);
        g.fill(x - 3, y + height + 2, x + width + 3, y + height + 3, 0xF0280070);
        g.fill(x - 3, y - 3, x - 2, y + height + 3, 0xF0280070);
        g.fill(x + width + 2, y - 3, x + width + 3, y + height + 3, 0xF0280070);

        int ty = y;
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x, ty, 0xFFFFFFFF);
            ty += i == 0 && lines.size() > 1 ? 12 : 10;
        }
        for (IconLine icon : icons) {
            ty += 2;
            g.text(font, seq(icon.label() + ":"), x, ty, 0xFFFFFFFF);
            ty += 11;
            for (int n = 0; n < icon.stacks().size(); n++) {
                int sx = x + (n % TIP_MAX_COLUMNS) * TIP_SLOT;
                int sy = ty + (n / TIP_MAX_COLUMNS) * TIP_SLOT;
                if (n == icon.active()) g.fill(sx, sy, sx + TIP_SLOT, sy + TIP_SLOT, 0x66555555);
                g.fakeItem(icon.stacks().get(n), sx + 1, sy + 1);
                String amount = n < icon.amounts().size() ? icon.amounts().get(n) : "";
                if (!amount.isEmpty()) overlayText(g, font, amount, sx + 1, sy + 1, 16, 16, 0xFFFFFF, Align.BOTTOM_RIGHT);
            }
            ty += iconRows(icon) * TIP_SLOT;
        }
        if (extraH > 0 && extra != null) {
            ty += 2;
            extra.paint(g, x, ty);
        }
    }

    /** A string with legacy colour codes (section sign) as drawable text. */
    static FormattedCharSequence seq(String text) {
        return Component.literal(text)
            .getVisualOrderText();
    }

    interface ExtraPainter {

        void paint(GuiGraphicsExtractor g, int x, int y);
    }
}
