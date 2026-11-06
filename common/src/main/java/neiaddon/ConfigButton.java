package neiaddon;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;

/** A button in JEI's bottom-left button row (after "bookmarks" and "history") that opens this addon's settings. */
public final class ConfigButton {

    private ConfigButton() {}

    private static boolean visible(Screen s) {
        return s != null && BookmarkPanel.supports(s) && NeiAddon.clientReady && Calculator.runtime != null
            && Calculator.runtime.getIngredientListOverlay()
                .isListDisplayed();
    }

    private static final String[] LABELS = { "N" };
    private static final String[] TIPS = { "NEI Features Options" };
    private static final String[] MODES = { "Recipe Mode", "Utility Mode", "Cheat Mode" };

    /** NEI's options button tooltip: its name, the cheat mode (gold: utility, red: cheat) and how to change it. */
    private static List<FormattedCharSequence> tipLines() {
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
        return JeiButtons.configX() + i * (JeiButtons.size() + 2);
    }

    private static int over(Screen s, double mx, double my) {
        int y = JeiButtons.configY(s.height);
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
                BookmarkPanel.button(g, Minecraft.getInstance().font, buttonX(i), JeiButtons.configY(s.height), JeiButtons.size(),
                    JeiButtons.size(), LABELS[i], hover == i);
            }
            if (hover >= 0) {
                g.renderTooltip(Minecraft.getInstance().font,
                    tipLines(), event.getMouseX(), event.getMouseY());
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
