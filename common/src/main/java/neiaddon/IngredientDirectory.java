package neiaddon;

import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * The ingredient directory: a middle click on a recipe slot that cycles through several items (any planks, any wool...)
 * opens a list of every item the slot accepts, next to the mouse (JEI++'s ingredient list). In the list a left click shows
 * an item's recipes, a right click its uses, R / U do the same and the bookmark key bookmarks it; the mouse wheel scrolls,
 * and Esc or a click outside closes it.
 */
public final class IngredientDirectory {

    private static final int CELL = 18;
    private static final int COLS = 9;
    private static final int ROWS = 6;
    private static final int PAD = 4;
    private static final int TITLE = 12;
    private static final int BACKGROUND = 0xF0100010;
    private static final int BORDER = 0xFF5000A0;
    private static final int HIGHLIGHT = 0x80FFFFFF;

    private static List<ItemStack> items = List.of();
    private static int x, y, scroll, hover = -1;

    private IngredientDirectory() {}

    static boolean open() {
        return !items.isEmpty();
    }

    static void close() {
        items = List.of();
        hover = -1;
    }

    /** The item under the mouse in the open list, else empty (for the bookmark key, item zoom...). */
    static ItemStack hoveredStack() {
        return hover >= 0 && hover < items.size() ? items.get(hover) : ItemStack.EMPTY;
    }

    private static int rows() {
        return Math.min(ROWS, (items.size() + COLS - 1) / COLS);
    }

    private static int width() {
        return PAD * 2 + Math.min(COLS, items.size()) * CELL;
    }

    private static int height() {
        return PAD * 2 + TITLE + rows() * CELL;
    }

    private static int indexAt(double mx, double my) {
        int gx = x + PAD;
        int gy = y + PAD + TITLE;
        if (mx < gx || my < gy) return -1;
        int col = (int) ((mx - gx) / CELL);
        int row = (int) ((my - gy) / CELL);
        if (col >= Math.min(COLS, items.size()) || row >= rows()) return -1;
        int index = (scroll + row) * COLS + col;
        return index < items.size() ? index : -1;
    }

    private static boolean inside(double mx, double my) {
        return mx >= x && mx < x + width() && my >= y && my < y + height();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen s = event.getScreen();
        double mx = event.getMouseX();
        double my = event.getMouseY();
        if (open()) {
            if (!(s instanceof IRecipesGui)) {
                close();
                return;
            }
            event.setCanceled(true);
            if (!inside(mx, my)) {
                close();
                return;
            }
            int i = indexAt(mx, my);
            if (i < 0) return;
            ItemStack stack = items.get(i);
            if (event.getButton() == 0 || event.getButton() == 1) {
                close();
                BookmarkPanel.showRecipes(stack, event.getButton() == 0 ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT);
            }
            return;
        }
        if (event.getButton() != 2 || !(s instanceof IRecipesGui)) return;
        List<RecipeShape.Alt> alts = RecipeHearts.hoveredSlotAlts();
        if (alts.size() < 2) return;
        List<ItemStack> list = new ArrayList<>();
        for (RecipeShape.Alt alt : alts) {
            if (!alt.stack()
                .isEmpty()) list.add(alt.stack());
        }
        if (list.size() < 2) return;
        items = list;
        scroll = 0;
        hover = -1;
        // beside the mouse, kept on the screen
        x = Math.max(2, Math.min((int) mx + 8, s.width - width() - 2));
        y = Math.max(2, Math.min((int) my - 8, s.height - height() - 2));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!open() || !inside(event.getMouseX(), event.getMouseY())) return;
        int pages = Math.max(0, (items.size() + COLS - 1) / COLS - ROWS);
        scroll = Math.max(0, Math.min(pages, scroll - (int) Math.signum(event.getScrollDelta())));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!open()) return;
        int code = event.getKeyCode();
        if (code == GLFW.GLFW_KEY_ESCAPE) {
            close();
            event.setCanceled(true);
            return;
        }
        ItemStack stack = hoveredStack();
        if (stack.isEmpty()) return;
        if (code == GLFW.GLFW_KEY_R || code == GLFW.GLFW_KEY_U) {
            close();
            BookmarkPanel.showRecipes(stack, code == GLFW.GLFW_KEY_R ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        if (!open()) return;
        if (!(event.getScreen() instanceof IRecipesGui)) {
            close();
            return;
        }
        hover = indexAt(event.getMouseX(), event.getMouseY());
        GuiGraphics g = event.getGuiGraphics();
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.identity();
        pose.translateZ(400f);
        try {
            draw(g, event.getMouseX(), event.getMouseY());
        } finally {
            pose.popMatrix();
        }
    }

    private static void draw(GuiGraphics g, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        int w = width();
        int h = height();
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, BACKGROUND);
        String title = items.size() + " accepted items";
        g.drawString(font, title, x + PAD, y + PAD, 0xFFE0E0E0, true);
        for (int row = 0; row < rows(); row++) {
            for (int col = 0; col < COLS; col++) {
                int index = (scroll + row) * COLS + col;
                if (index >= items.size()) break;
                int cx = x + PAD + col * CELL;
                int cy = y + PAD + TITLE + row * CELL;
                ItemStack stack = items.get(index);
                if (index == hover) g.fill(cx, cy, cx + CELL, cy + CELL, HIGHLIGHT);
                FluidStacks.item(g, stack, cx + 1, cy + 1);
                g.renderItemDecorations(font, stack, cx + 1, cy + 1, null);
            }
        }
        if (hover >= 0) tooltip(g, font, items.get(hover), mx, my);
    }

    private static void tooltip(GuiGraphics g, Font font, ItemStack stack, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        List<Component> tip = new ArrayList<>(stack.getTooltipLines(mc.player, net.minecraft.world.item.TooltipFlag.Default.NORMAL));
        if (tip.isEmpty()) tip.add(FluidStacks.name(stack));
        tip.add(Component.literal("§7Click: recipes, right click: uses"));
        HotkeyTips.addModName(tip, stack);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : tip) lines.addAll(font.split(line, 260));
        g.renderTooltip(font, lines, mx, my);
    }
}
