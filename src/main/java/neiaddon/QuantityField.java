package neiaddon;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * NEI's item quantity field (ItemQuantityField + the "+" / "-" buttons). The number is how many items "with count"
 * actions use and the step of Ctrl+Alt+scroll; 0 shows "One stack" and means a stack of the item. Sits just above JEI's
 * search box in the item list on the right. Wheel over the field: +/-1, Shift 10, Ctrl 64, like NEI.
 */
@EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT)
public final class QuantityField {

    private static final int H = 20;
    private static final int BTN = 20;
    private static final int GAP = 2;
    private static final int MIN_WIDTH = 70;
    private static final int MARGIN = 6;
    private static final int MAX_DIGITS = 9;

    private static boolean focused;
    private static String text = "";

    private QuantityField() {}

    public static int quantity() {
        return AddonConfig.ITEM_QUANTITY.get();
    }

    /** NEI's NEIClientUtils.getScrollMultiplier: the quantity, or a stack of the item when it is 0. */
    public static long scrollMultiplier(ItemStack stack) {
        int q = quantity();
        return q != 0 ? q : Math.max(1, stack.getMaxStackSize());
    }

    public static boolean focused() {
        return focused;
    }

    private static void set(int value) {
        AddonConfig.ITEM_QUANTITY.set(Math.max(0, value));
    }

    private static boolean visible(Screen s) {
        return s != null && BookmarkPanel.supports(s) && width(s) >= MIN_WIDTH && Calculator.runtime != null
            && Calculator.runtime.getIngredientListOverlay()
                .isListDisplayed();
    }

    // NEI's layout when the search box is not in the row: "-", the field, "+" across the whole item list
    private static int x(Screen s) {
        int guiLeft = s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> c ? c.getGuiLeft()
            : (int) (s.width * 0.26);
        return s.width - guiLeft + MARGIN;
    }

    private static int width(Screen s) {
        return s.width - MARGIN - x(s);
    }

    private static int y(Screen s) {
        // NEI: the bottom row of the item list; with JEI's search box there (the default) it goes just above it
        return s.height - MARGIN - H - (JeiButtons.searchCentered() ? 0 : 2 + H);
    }

    /** The area JEI should keep its item grid out of, or none. */
    public static java.util.List<net.minecraft.client.renderer.Rect2i> extraAreas() {
        Screen s = McCompat.screen();
        if (!visible(s)) return java.util.List.of();
        return java.util.List.of(new net.minecraft.client.renderer.Rect2i(x(s), y(s), width(s), H));
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int step() {
        Minecraft mc = Minecraft.getInstance();
        return McCompat.ctrl() ? 64 : McCompat.shift() ? 10 : 1;
    }

    private static void focus() {
        focused = true;
        text = quantity() == 0 ? "" : Integer.toString(quantity());
    }

    private static void unfocus() {
        if (!focused) return;
        focused = false;
        AddonConfig.ITEM_QUANTITY.save();
    }

    private static void edit(String next) {
        text = next;
        try {
            set(text.isEmpty() ? 0 : Integer.parseInt(text));
        } catch (NumberFormatException e) {
            set(0);
        }
    }

    // ---------------------------------------------------------------- drawing

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onForeground(ScreenEvent.Render.Post event) {
        Screen s = event.getScreen();
        if (!visible(s)) {
            focused = false;
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.identity();
        pose.translateZ(100f);
        try {
            draw(g, s, event.getMouseX(), event.getMouseY());
        } finally {
            pose.popMatrix();
        }
    }

    private static void draw(GuiGraphics g, Screen s, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        int x = x(s) + BTN + GAP;
        int w = width(s) - 2 * (BTN + GAP);
        int y = y(s);
        g.fill(x, y, x + w, y + H, focused ? 0xFFFFFFFF : 0xFFA0A0A0);
        g.fill(x + 1, y + 1, x + w - 1, y + H - 1, 0xFF000000);
        String shown;
        int color = 0xFFE0E0E0;
        if (focused) {
            shown = text + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
        } else if (quantity() == 0) {
            shown = "One stack";
            color = 0xFF808080;
        } else {
            shown = Integer.toString(quantity());
        }
        g.drawString(font, shown, x + (w - font.width(shown)) / 2, y + (H - 8) / 2, color, true);
        int less = x(s);
        int more = x(s) + width(s) - BTN;
        BookmarkPanel.button(g, font, less, y, BTN, H, "-", in(mx, my, less, y, BTN, H));
        BookmarkPanel.button(g, font, more, y, BTN, H, "+", in(mx, my, more, y, BTN, H));
    }

    // ---------------------------------------------------------------- input

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen s = event.getScreen();
        if (!visible(s)) return;
        double mx = event.getMouseX();
        double my = event.getMouseY();
        int x = x(s);
        int y = y(s);
        int more = x + width(s) - BTN;
        if (in(mx, my, x + BTN + GAP, y, width(s) - 2 * (BTN + GAP), H)) {
            if (!focused) focus();
            event.setCanceled(true);
        } else if (in(mx, my, x, y, BTN, H) || in(mx, my, more, y, BTN, H)) {
            if (event.getButton() == 0) {
                set(quantity() + (mx >= more ? step() : -step()));
                AddonConfig.ITEM_QUANTITY.save();
            }
            event.setCanceled(true);
        } else {
            unfocus();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        Screen s = event.getScreen();
        if (!visible(s) || !in(event.getMouseX(), event.getMouseY(), x(s) + BTN + GAP, y(s), width(s) - 2 * (BTN + GAP), H)) return;
        int dir = (int) Math.signum(event.getScrollDeltaY());
        if (dir != 0) {
            set(quantity() + dir * step());
            AddonConfig.ITEM_QUANTITY.save();
            if (focused) text = quantity() == 0 ? "" : Integer.toString(quantity());
        }
        event.setCanceled(true);
    }

    /** While the field has focus every key belongs to it (so A, E, T... do not trigger anything). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!focused) return;
        event.setCanceled(true);
        int code = event.getKeyCode();
        if (code == GLFW.GLFW_KEY_ESCAPE || code == GLFW.GLFW_KEY_ENTER || code == GLFW.GLFW_KEY_KP_ENTER) {
            unfocus();
        } else if (code == GLFW.GLFW_KEY_BACKSPACE) {
            if (!text.isEmpty()) edit(text.substring(0, text.length() - 1));
        } else if (code == GLFW.GLFW_KEY_UP || code == GLFW.GLFW_KEY_DOWN) {
            edit(Integer.toString(Math.max(0, quantity() + (code == GLFW.GLFW_KEY_UP ? 1 : -1))));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onChar(ScreenEvent.CharacterTyped.Pre event) {
        if (!focused) return;
        event.setCanceled(true);
        int c = event.getCodePoint();
        if (c >= '0' && c <= '9' && text.length() < MAX_DIGITS) edit(text + c);
    }
}
