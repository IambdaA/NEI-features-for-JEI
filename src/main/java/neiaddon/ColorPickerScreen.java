package neiaddon;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * A color picker for the RGB options: a square for saturation (across) and brightness (down), a bar for the hue, the
 * old and new color side by side and the hex value. Drag in the square or the bar; the color applies when you press
 * Done. Mouse input comes through screen events, like the other screens of this addon.
 */
@EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT)
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
        return width / 2 - (SIZE + 10 + BAR) / 2;
    }

    private int top() {
        return height / 2 - SIZE / 2 - 10;
    }

    private boolean inSquare(double mx, double my) {
        return mx >= left() && mx < left() + SIZE && my >= top() && my < top() + SIZE;
    }

    private boolean inBar(double mx, double my) {
        int x = left() + SIZE + 10;
        return mx >= x && mx < x + BAR && my >= top() && my < top() + SIZE;
    }

    private boolean inDone(double mx, double my) {
        int x = width / 2 - 100;
        int y = top() + SIZE + 56;
        return mx >= x && mx < x + 200 && my >= y && my < y + 20;
    }

    private void pick(double mx, double my) {
        if (drag == 1) {
            sat = (float) Math.max(0, Math.min(1, (mx - left()) / (SIZE - 1)));
            val = (float) Math.max(0, Math.min(1, 1 - (my - top()) / (SIZE - 1)));
        } else if (drag == 2) {
            hue = (float) Math.max(0, Math.min(359.9, (my - top()) / (SIZE - 1) * 360));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        var font = Minecraft.getInstance().font;
        g.drawString(font, title, (width - font.width(title)) / 2, top() - 22, 0xFFFFFFFF, true);
        int l = left();
        int t = top();
        for (int cx = 0; cx < SIZE; cx += STEP) {
            for (int cy = 0; cy < SIZE; cy += STEP) {
                int rgb = fromHsv(hue, cx / (float) (SIZE - 1), 1 - cy / (float) (SIZE - 1));
                g.fill(l + cx, t + cy, l + Math.min(cx + STEP, SIZE), t + Math.min(cy + STEP, SIZE), 0xFF000000 | rgb);
            }
        }
        int bx = l + SIZE + 10;
        for (int cy = 0; cy < SIZE; cy += 2) {
            g.fill(bx, t + cy, bx + BAR, t + Math.min(cy + 2, SIZE), 0xFF000000 | fromHsv(cy / (float) (SIZE - 1) * 360, 1, 1));
        }
        // handles
        int hx = l + Math.round(sat * (SIZE - 1));
        int hy = t + Math.round((1 - val) * (SIZE - 1));
        g.fill(hx - 3, hy - 3, hx + 4, hy + 4, 0xFFFFFFFF);
        g.fill(hx - 2, hy - 2, hx + 3, hy + 3, 0xFF000000 | current());
        int by = t + Math.round(hue / 360f * (SIZE - 1));
        g.fill(bx - 2, by - 1, bx + BAR + 2, by + 2, 0xFFFFFFFF);
        // old / new and the value
        int y = t + SIZE + 10;
        g.fill(l, y, l + 60, y + 20, 0xFF000000 | before);
        g.fill(l + 60, y, l + 120, y + 20, 0xFF000000 | current());
        g.drawString(font, "old", l + 4, y + 6, 0xFFFFFFFF, true);
        g.drawString(font, "new", l + 64, y + 6, 0xFFFFFFFF, true);
        String hex = String.format("#%06X", current());
        g.drawString(font, hex, l + SIZE + 10 + BAR - font.width(hex), y + 6, 0xFFFFFFFF, true);
        BookmarkPanel.button(g, font, width / 2 - 100, t + SIZE + 56, 200, 20, "Done", inDone(mouseX, mouseY));
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
