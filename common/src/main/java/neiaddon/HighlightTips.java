package neiaddon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import neiaddon.platform.Platform;
import org.apache.commons.lang3.StringUtils;

/**
 * NEI's "highlight tips" ({@code HUDRenderer}): a tooltip-style box naming the BLOCK under the crosshair, with its
 * item. Off by default, toggled with Numpad 0, hidden while the player list key is held. Position is
 * {@code hudX}/{@code hudY} (0..10000, NEI defaults 5000/100); a screen to drag it is bound to a key in Controls.
 */
public final class HighlightTips {

    /** The overlay id the loader registers the tip under. */
    public static final String LAYER_ID = "highlight_tips";

    /** The tip, drawn by the loader above the crosshair. */
    public static HighlightTips layer() {
        return new HighlightTips();
    }

    private static String modName(String namespace) {
        return Platform.get()
            .modDisplayName(namespace)
            .orElseGet(() -> StringUtils.capitalize(namespace));
    }

    public void render(GuiGraphics g, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        // NEI: only without a screen, in a world, while the player list key is not held, and only for blocks
        if (!AddonConfig.HUD_ENABLED.get() || mc.level == null || mc.player == null) return;
        if (McCompat.screen() != null || mc.options.keyPlayerList.isDown()) return;
        if (!(mc.hitResult instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = block.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return;
        ItemStack icon = state.getBlock().getCloneItemStack(mc.level, pos, state);
        if (icon.isEmpty()) return;

        List<String> lines = new ArrayList<>();
        String name = icon.getHoverName().getString();
        // NEI's DefaultHighlightHandler appends the power level to redstone dust
        if (state.is(Blocks.REDSTONE_WIRE)) {
            String power = String.valueOf(state.getValue(RedStoneWireBlock.POWER));
            name = name + " " + (power.length() < 2 ? " " + power : power);
        }
        lines.add(name);
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (AddonConfig.HUD_SHOW_ID.get()) lines.add(id.toString());
        if (AddonConfig.HUD_SHOW_MOD.get()) lines.add(modName(id.getNamespace()));
        draw(g, mc.font, icon, lines, AddonConfig.HUD_X.get(), AddonConfig.HUD_Y.get());
    }

    /** NEI's HUDRenderer.renderOverlay geometry: {x, y, w, h} of the box for a position given in 0..10000. */
    static int[] box(Font font, List<String> lines, int posX, int posY, int screenW, int screenH) {
        int w = 0;
        for (String s : lines) w = Math.max(w, font.width(s) + 29);
        int h = Math.max(24, 10 + 10 * lines.size());
        int x = (screenW - w - 1) * posX / 10000;
        int y = (screenH - h - 1) * posY / 10000;
        return new int[] { x, y, w, h };
    }

    static void draw(GuiGraphics g, Font font, ItemStack icon, List<String> lines, int posX, int posY) {
        int[] b = box(font, lines, posX, posY, g.guiWidth(), g.guiHeight());
        int x = b[0], y = b[1], w = b[2], h = b[3];
        tooltipBox(g, x, y, w, h);
        int ty = (h - 8 * lines.size()) / 2;
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x + 24, y + ty + 10 * i, 0xFFA0A0A0, true);
        }
        if (!icon.isEmpty()) g.renderFakeItem(icon, x + 5, y + h / 2 - 8);
    }

    /** The vanilla 1.7.10 tooltip box NEI's GuiDraw.drawTooltipBox draws. */
    private static void tooltipBox(GuiGraphics g, int x, int y, int w, int h) {
        int bg = 0xF0100010;
        int c1 = 0x505000FF;
        int c2 = 0x5028007F;
        g.fill(x + 1, y, x + w - 1, y + 1, bg);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, bg);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);
        g.fill(x, y + 1, x + 1, y + h - 1, bg);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, bg);
        int mid = y + h / 2;
        g.fill(x + 1, y + 2, x + 2, mid, c1);
        g.fill(x + 1, mid, x + 2, y + h - 2, c2);
        g.fill(x + w - 2, y + 2, x + w - 1, mid, c1);
        g.fill(x + w - 2, mid, x + w - 1, y + h - 2, c2);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, c1);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, c2);
    }
}
