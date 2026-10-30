package neiaddon;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/**
 * NEI's GuiHighlightTips: shows a sample of the highlight tip that you drag to where you want it. Esc closes it.
 * The position is saved to hudX / hudY.
 */
public final class HudPositionScreen extends Screen {

    private boolean dragging;
    private int grabX, grabY;

    private HudPositionScreen() {
        super(Component.literal("Highlight Tooltips"));
    }

    public static void open() {
        McCompat.setScreen(new HudPositionScreen());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        var font = Minecraft.getInstance().font;
        List<String> lines = List.of("Sample");
        int[] b = HighlightTips.box(font, lines, AddonConfig.HUD_X.get(), AddonConfig.HUD_Y.get(), width, height);

        boolean down = GLFW.glfwGetMouseButton(Minecraft.getInstance().getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT)
            == GLFW.GLFW_PRESS;
        if (down && !dragging && mouseX >= b[0] && mouseX < b[0] + b[2] && mouseY >= b[1] && mouseY < b[1] + b[3]) {
            dragging = true;
            grabX = mouseX - b[0];
            grabY = mouseY - b[1];
        } else if (!down && dragging) {
            dragging = false;
            AddonConfig.HUD_X.save();
            AddonConfig.HUD_Y.save();
        }
        if (dragging) {
            int freeW = Math.max(1, width - b[2] - 1);
            int freeH = Math.max(1, height - b[3] - 1);
            AddonConfig.HUD_X.set(Math.max(0, Math.min(10000, (mouseX - grabX) * 10000 / freeW)));
            AddonConfig.HUD_Y.set(Math.max(0, Math.min(10000, (mouseY - grabY) * 10000 / freeH)));
        }

        HighlightTips.draw(g, font, new ItemStack(Items.DIAMOND_BLOCK), lines, AddonConfig.HUD_X.get(), AddonConfig.HUD_Y.get());
        String hint = "Drag the box to move it. Esc to close.";
        g.text(font, hint, (width - font.width(hint)) / 2, height - 20, 0xFFFFFFFF, true);
    }
}
