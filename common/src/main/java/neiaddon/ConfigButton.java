package neiaddon;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;
import neiaddon.platform.ClientPlatform;

/**
 * Buttons in JEI's bottom-left button row (after "bookmarks" and "history"): N opens this addon's settings, S the subsets
 * menu ({@link Subsets}). With the Top position the subsets button is "Item Subsets" at the top centre of the screen
 * instead, and the row keeps only N, while the window leaves room above it (otherwise S is back in the row).
 */
public final class ConfigButton {

    private ConfigButton() {}

    private static boolean visible(Screen s) {
        return s != null && BookmarkPanel.supports(s) && NeiAddon.clientReady && Calculator.runtime != null
            && Calculator.runtime.getIngredientListOverlay()
                .isListDisplayed();
    }

    private static final String[] LABELS = { "N", "S" };
    private static final String[] TIPS = { "NEI Features Options", "Subsets" };
    private static final String[] MODES = { "Recipe Mode", "Utility Mode", "Cheat Mode" };
    /** The top subsets button: NEI's name, 150 x 16, centred at the top of the screen. */
    private static final String TOP_LABEL = "Item Subsets";
    private static final int TOP_W = 150;
    private static final int TOP_H = 16;
    private static final int TOP_Y = 2;
    /** The top button is drawn only when the container's window starts more than this many pixels below the screen top. */
    private static final int TOP_ROOM = 20;

    /** NEI's options button tooltip: its name, the cheat mode (gold: utility, red: cheat) and how to change it. */
    private static List<FormattedCharSequence> tipLines(int button) {
        if (button == 1) {
            return List.of(Component.literal(TIPS[1])
                .getVisualOrderText(),
                Component.literal(EmiCompat.installed() ? "Show or hide items by type and mod" : "Show or hide items by type, mod and creative tab")
                    .withStyle(ChatFormatting.GRAY)
                    .getVisualOrderText());
        }
        int mode = Math.max(0, Math.min(AddonConfig.CHEAT_MODE.get(), 2));
        ChatFormatting color = mode == 1 ? ChatFormatting.GOLD : mode == 2 ? ChatFormatting.RED : ChatFormatting.WHITE;
        return List.of(Component.literal(TIPS[0])
            .getVisualOrderText(),
            Component.literal(MODES[mode])
                .withStyle(color)
                .getVisualOrderText(),
            Component.literal("Ctrl-click here to cycle between Recipe, Utility, and Cheat Mode.")
                .withStyle(color)
                .getVisualOrderText());
    }

    /**
     * The top subsets button's rectangle when it is drawn on this screen: the Top position, a container whose window
     * starts more than 20 px below the screen top, and no other mod's widget where it would be. Otherwise null.
     */
    static Rect2i topRect(Screen s) {
        if (!AddonConfig.SUBSETS.get() || AddonConfig.SUBSETS_POSITION.get() != 1) return null;
        if (!(s instanceof AbstractContainerScreen<?> c)) return null;
        if (ClientPlatform.get()
            .containerBounds(c)[1] <= TOP_ROOM) return null;
        Rect2i r = new Rect2i((s.width - TOP_W) / 2, TOP_Y, TOP_W, TOP_H);
        for (Rect2i w : ScreenAreas.widgets(s)) {
            if (w.getX() < r.getX() + r.getWidth() && r.getX() < w.getX() + w.getWidth()
                && w.getY() < r.getY() + r.getHeight() && r.getY() < w.getY() + w.getHeight()) return null;
        }
        return r;
    }

    /** Is the S button drawn: the subsets setting is on and the top button is not in its place. */
    private static boolean sShown(Screen s) {
        return AddonConfig.SUBSETS.get() && topRect(s) == null;
    }

    /** How many buttons the N row has: N, and S when it is drawn. */
    private static int count(Screen s) {
        return sShown(s) ? LABELS.length : 1;
    }

    /** Is the button a menu hangs from still drawn on this screen? {@code downward}: the top button, else the S button. */
    static boolean anchorShown(Screen s, boolean downward) {
        return visible(s) && (downward ? topRect(s) != null : sShown(s));
    }

    private static int buttonX(int i) {
        return startX() + i * (JeiButtons.size() + 2);
    }

    /**
     * After JEI's last button (with EMI: after EMI's own bottom-left buttons), moved further right past any widget another
     * mod put in that row (ATM9's "Dark Mode" button, for one), so the two never sit on top of each other.
     */
    private static int startX() {
        Screen s = Minecraft.getInstance().screen;
        List<Rect2i> emi = EmiCompat.buttonAreas();
        int x = emi.isEmpty() ? JeiButtons.configX() : emi.get(emi.size() - 1)
            .getX() + emi.get(emi.size() - 1)
                .getWidth() + 2;
        if (s == null) return x;
        int y = rowY(s);
        int size = JeiButtons.size();
        int w = count(s) * (size + 2) - 2;
        List<Rect2i> widgets = ScreenAreas.widgets(s);
        for (int guard = 0; guard < 16; guard++) {
            Rect2i hit = null;
            for (Rect2i r : widgets) {
                if (r.getX() < x + w && x < r.getX() + r.getWidth() && r.getY() < y + size && y < r.getY() + r.getHeight()) {
                    hit = r;
                    break;
                }
            }
            if (hit == null) break;
            x = hit.getX() + hit.getWidth() + 2;
        }
        return x;
    }

    /** The row's top: EMI's last bottom-left button's (the one the row follows) when EMI shows them, else JEI's button row's. */
    private static int rowY(Screen s) {
        List<Rect2i> emi = EmiCompat.buttonAreas();
        return emi.isEmpty() ? JeiButtons.configY(s.height) : emi.get(emi.size() - 1)
            .getY();
    }

    private static boolean inside(Rect2i r, double mx, double my) {
        return mx >= r.getX() && mx < r.getX() + r.getWidth() && my >= r.getY() && my < r.getY() + r.getHeight();
    }

    /** Is the point over the subsets button (the top one, or S)? */
    static boolean overSubsets(Screen s, double mx, double my) {
        if (!visible(s)) return false;
        Rect2i top = topRect(s);
        if (top != null) return inside(top, mx, my);
        return sShown(s) && over(s, mx, my) == 1;
    }

    private static int over(Screen s, double mx, double my) {
        int y = rowY(s);
        if (my < y || my >= y + JeiButtons.size()) return -1;
        for (int i = 0; i < count(s); i++) {
            if (mx >= buttonX(i) && mx < buttonX(i) + JeiButtons.size()) return i;
        }
        return -1;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onForeground(ScreenEvent.Render.Post event) {
        Screen s = event.getScreen();
        if (!visible(s)) return;
        GuiGraphics g = event.getGuiGraphics();
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.identity();
        pose.translateZ(100f);
        try {
            int mx = event.getMouseX();
            int my = event.getMouseY();
            Rect2i top = topRect(s);
            boolean topHover = top != null && inside(top, mx, my);
            int hover = over(s, mx, my);
            if (top != null) {
                BookmarkPanel.button(g, Minecraft.getInstance().font, top.getX(), top.getY(), top.getWidth(), top.getHeight(),
                    TOP_LABEL, topHover);
            }
            for (int i = 0; i < count(s); i++) {
                BookmarkPanel.button(g, Minecraft.getInstance().font, buttonX(i), rowY(s), JeiButtons.size(),
                    JeiButtons.size(), LABELS[i], hover == i);
            }
            if (topHover) {
                g.renderTooltip(Minecraft.getInstance().font,
                    tipLines(1), mx, my);
            } else if (hover >= 0) {
                g.renderTooltip(Minecraft.getInstance().font,
                    tipLines(hover), mx, my);
            }
        } finally {
            pose.popMatrix();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen s = event.getScreen();
        if (!visible(s)) return;
        Rect2i top = topRect(s);
        if (top != null && inside(top, event.getMouseX(), event.getMouseY())) {
            if (event.getButton() == 0) {
                clickSound();
                Subsets.click(s, top.getX(), top.getY() + top.getHeight(), true);
            }
            event.setCanceled(true);
            return;
        }
        int hit = over(s, event.getMouseX(), event.getMouseY());
        if (hit < 0) return;
        if (hit == 1) {
            if (event.getButton() == 0) {
                clickSound();
                Subsets.click(s, buttonX(1), rowY(s), false);
            }
            event.setCanceled(true);
            return;
        }
        if (event.getButton() == 0) {
            clickSound();
            if (McCompat.ctrl()) {
                // NEI: Ctrl + click on the options button cycles Recipe, Utility and Cheat Mode
                AddonConfig.CHEAT_MODE.set((AddonConfig.CHEAT_MODE.get() + 1) % 3);
                AddonConfig.CHEAT_MODE.save();
            } else {
                NeiSettings.open(s);
            }
        }
        event.setCanceled(true);
    }

    /** The default button click, as every other button plays it. */
    private static void clickSound() {
        Minecraft.getInstance()
            .getSoundManager()
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
