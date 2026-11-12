package neiaddon;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;

/**
 * Buttons in JEI's bottom-left button row (after "bookmarks" and "history"): N opens this addon's settings, S the subsets
 * menu ({@link Subsets}).
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

    /** NEI's options button tooltip: its name, the cheat mode (gold: utility, red: cheat) and how to change it. */
    private static List<FormattedCharSequence> tipLines(int button) {
        if (button == 1) {
            return List.of(Component.literal(TIPS[1])
                .getVisualOrderText(),
                Component.literal(EmiCompat.installed() ? "Show only one mod in EMI's list (sets EMI's search)"
                    : "Show only one mod or creative tab in JEI's list (sets JEI's search)")
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
        int w = LABELS.length * (size + 2) - 2;
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

    /** Is the point over the subsets button (S)? */
    static boolean overSubsets(Screen s, double mx, double my) {
        return s != null && visible(s) && over(s, mx, my) == 1;
    }

    private static int over(Screen s, double mx, double my) {
        int y = rowY(s);
        if (my < y || my >= y + JeiButtons.size()) return -1;
        for (int i = 0; i < LABELS.length; i++) {
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
            int hover = over(s, event.getMouseX(), event.getMouseY());
            for (int i = 0; i < LABELS.length; i++) {
                BookmarkPanel.button(g, Minecraft.getInstance().font, buttonX(i), rowY(s), JeiButtons.size(),
                    JeiButtons.size(), LABELS[i], hover == i);
            }
            if (hover >= 0) {
                g.renderTooltip(Minecraft.getInstance().font,
                    tipLines(hover), event.getMouseX(), event.getMouseY());
            }
        } finally {
            pose.popMatrix();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen s = event.getScreen();
        if (!visible(s)) return;
        int hit = over(s, event.getMouseX(), event.getMouseY());
        if (hit < 0) return;
        if (hit == 1) {
            if (event.getButton() == 0) Subsets.toggle(s, buttonX(1), rowY(s));
            event.setCanceled(true);
            return;
        }
        if (event.getButton() == 0) {
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
}
