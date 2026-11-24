package neiaddon;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;

/**
 * A color picker for the RGB options: a square for saturation (across) and brightness (down), a bar for the hue, the
 * old and new color side by side and the hex value. Drag in the square or the bar; the color applies when you press
 * Done. Mouse input comes through screen events, like the other screens of this addon.
 */
public final class ColorPickerScreen extends Screen {

    private static final int SIZE = 120;
    private static final int STEP = 4;
    private static final int BAR = 14;

    private final Screen parent;
    private final IntConsumer apply;
    private final int before;
    private float hue, sat, val;
    private int drag; // 0 none, 1 square, 2 hue bar

    ColorPickerScreen(Screen parent, Component title, IntSupplier current, IntConsumer apply) {
        super(title);
        this.parent = parent;
        this.apply = apply;
        this.before = current.getAsInt() & 0xFFFFFF;
        float[] hsv = toHsv(before);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
    }

    // ---------------------------------------------------------------- color maths

    static int fromHsv(float h, float s, float v) {
        float c = v * s;
        float hp = (h % 360f) / 60f;
        float x = c * (1 - Math.abs(hp % 2 - 1));
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else { r = c; b = x; }
        float m = v - c;
        return (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
    }

    static float[] toHsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f;
        float g = ((rgb >> 8) & 255) / 255f;
        float b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) h = 60 * (((g - b) / d + 6) % 6);
            else if (max == g) h = 60 * ((b - r) / d + 2);
            else h = 60 * ((r - g) / d + 4);
        }
        return new float[] { h, max == 0 ? 0 : d / max, max };
    }

    private int current() {
        return fromHsv(hue, sat, val);
    }

    // ---------------------------------------------------------------- layout

    private int left() {
        return width / 2 - (size() + 10 + BAR) / 2;
    }

    /** The square's side: SIZE, smaller on a short screen (the hue bar follows it). */
    private int size() {
        return Math.max(60, Math.min(SIZE, height - 102));
    }

    /**
     * The square's top: centred as before, moved up just enough on a short screen that the Done button (76 below the
     * square's top edge plus its size) stays on it, and never so high that the title (22 above) leaves it.
     */
    private int top() {
        return Math.max(24, Math.min(height / 2 - size() / 2 - 10, height - size() - 78));
    }

    private boolean inSquare(double mx, double my) {
        return mx >= left() && mx < left() + size() && my >= top() && my < top() + size();
    }

    private boolean inBar(double mx, double my) {
        int x = left() + size() + 10;
        return mx >= x && mx < x + BAR && my >= top() && my < top() + size();
    }

    private boolean inDone(double mx, double my) {
        int x = width / 2 - 100;
        int y = top() + size() + 56;
        return mx >= x && mx < x + 200 && my >= y && my < y + 20;
    }

    private void pick(double mx, double my) {
        if (drag == 1) {
            sat = (float) Math.max(0, Math.min(1, (mx - left()) / (size() - 1)));
            val = (float) Math.max(0, Math.min(1, 1 - (my - top()) / (size() - 1)));
        } else if (drag == 2) {
            hue = (float) Math.max(0, Math.min(359.9, (my - top()) / (size() - 1) * 360));
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        var font = Minecraft.getInstance().font;
        g.text(font, title, (width - font.width(title)) / 2, top() - 22, 0xFFFFFFFF, true);
        int l = left();
        int t = top();
        for (int cx = 0; cx < size(); cx += STEP) {
            for (int cy = 0; cy < size(); cy += STEP) {
                int rgb = fromHsv(hue, cx / (float) (size() - 1), 1 - cy / (float) (size() - 1));
                g.fill(l + cx, t + cy, l + Math.min(cx + STEP, size()), t + Math.min(cy + STEP, size()), 0xFF000000 | rgb);
            }
        }
        int bx = l + size() + 10;
        for (int cy = 0; cy < size(); cy += 2) {
            g.fill(bx, t + cy, bx + BAR, t + Math.min(cy + 2, size()), 0xFF000000 | fromHsv(cy / (float) (size() - 1) * 360, 1, 1));
        }
        // handles
        int hx = l + Math.round(sat * (size() - 1));
        int hy = t + Math.round((1 - val) * (size() - 1));
        g.fill(hx - 3, hy - 3, hx + 4, hy + 4, 0xFFFFFFFF);
        g.fill(hx - 2, hy - 2, hx + 3, hy + 3, 0xFF000000 | current());
        int by = t + Math.round(hue / 360f * (size() - 1));
        g.fill(bx - 2, by - 1, bx + BAR + 2, by + 2, 0xFFFFFFFF);
        // old / new and the value
        int y = t + size() + 10;
        g.fill(l, y, l + 60, y + 20, 0xFF000000 | before);
        g.fill(l + 60, y, l + 120, y + 20, 0xFF000000 | current());
        g.text(font, "old", l + 4, y + 6, 0xFFFFFFFF, true);
        g.text(font, "new", l + 64, y + 6, 0xFFFFFFFF, true);
        String hex = String.format("#%06X", current());
        g.text(font, hex, l + size() + 10 + BAR - font.width(hex), y + 6, 0xFFFFFFFF, true);
        BookmarkPanel.button(g, font, width / 2 - 100, t + size() + 56, 200, 20, "Done", inDone(mouseX, mouseY));
    }

    @Override
    public void onClose() {
        McCompat.setScreen(parent);
    }

    // ---------------------------------------------------------------- input

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ColorPickerScreen s)) return;
        event.setCanceled(true);
        double mx = event.getMouseX();
        double my = event.getMouseY();
        if (s.inDone(mx, my)) {
            s.apply.accept(s.current());
            s.onClose();
        } else if (s.inSquare(mx, my)) {
            s.drag = 1;
            s.pick(mx, my);
        } else if (s.inBar(mx, my)) {
            s.drag = 2;
            s.pick(mx, my);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!(event.getScreen() instanceof ColorPickerScreen s)) return;
        event.setCanceled(true);
        s.pick(event.getMouseX(), event.getMouseY());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!(event.getScreen() instanceof ColorPickerScreen s)) return;
        event.setCanceled(true);
        s.drag = 0;
    }
}
