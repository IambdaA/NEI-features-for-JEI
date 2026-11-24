package neiaddon;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import org.joml.Matrix3x2fStack;

/**
 * The ingredient directory: a middle click on a recipe slot that cycles through several items (any planks, any wool...)
 * opens a list of every item the slot accepts, next to the mouse (JEI++'s ingredient list). In the list a left click shows
 * an item's recipes, a right click its uses, JEI's show-recipes / show-uses keys (R / U) do the same and the bookmark key
 * bookmarks it; the mouse wheel scrolls, and Esc or a click outside closes it.
 * <p>The tag chooser (#39) reuses the list in a select mode: a middle click on a tag slot of the recipe
 * tree or the bookmark panel opens it, a left click takes that item for the clicked slot and Ctrl + left click also
 * remembers it as the tag's default ({@link TagDefaults}). The chosen item gets a frame, the saved default a "D".
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
    private static final int MARK = 0xFFFFE000;

    private static List<ItemStack> items = List.of();
    private static int x, y, scroll, hover = -1;
    // select mode (#39): the entry being chosen for, the ids shown, the screen holding the popup
    private static Bookmarks.Entry selecting;
    private static List<String> optionIds = List.of();
    private static Screen host;

    private IngredientDirectory() {}

    static boolean open() {
        return !items.isEmpty();
    }

    static void close() {
        items = List.of();
        hover = -1;
        selecting = null;
        optionIds = List.of();
        host = null;
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

    /**
     * Open the list in select mode for one bookmark entry (#39) whose ingredient accepts several items.
     * A left click takes the item for that case, Ctrl + left click also sets it as the tag's default. False when
     * there is nothing to choose.
     */
    static boolean openSelect(Bookmarks.Entry entry, Screen s, double mx, double my) {
        if (entry == null || !Bookmarks.hasOptions(entry)) return false;
        List<ItemStack> list = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String id : entry.options) {
            ItemStack stack = FluidStacks.stackOf(id);
            if (!stack.isEmpty()) {
                list.add(stack);
                ids.add(id);
            }
        }
        if (ids.size() < 2) return false;
        close();
        items = list;
        optionIds = ids;
        selecting = entry;
        host = s;
        scroll = 0;
        hover = -1;
        // beside the mouse, kept on the screen
        x = Math.max(2, Math.min((int) mx + 8, s.width - width() - 2));
        y = Math.max(2, Math.min((int) my - 8, s.height - height() - 2));
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen s = event.getScreen();
        double mx = event.getMouseX();
        double my = event.getMouseY();
        if (open()) {
            if (selecting == null ? !RecipeHearts.isRecipesScreen(s) : s != host) {
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
            if (selecting != null) {
                // the chooser (issue #39): left click takes the item, Ctrl takes it and remembers it as the default
                if (event.getButton() == 0) {
                    String id = optionIds.get(i);
                    boolean remember = Minecraft.getInstance().hasControlDown();
                    Bookmarks.Entry entry = selecting;
                    Screen where = host;
                    close();
                    if (remember) TagDefaults.setDefault(entry.options, id);
                    if (Bookmarks.setOption(entry, id) && where instanceof CraftingTreeScreen tree) {
                        tree.tagChoiceApplied();
                    }
                }
                return;
            }
            ItemStack stack = items.get(i);
            if (event.getButton() == 0 || event.getButton() == 1) {
                close();
                BookmarkPanel.showRecipes(stack, event.getButton() == 0 ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT);
            }
            return;
        }
        if (event.getButton() != 2 || !RecipeHearts.isRecipesScreen(s)) return;
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
        scroll = Math.max(0, Math.min(pages, scroll - (int) Math.signum(event.getScrollDeltaY())));
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
        if (selecting != null) {
            // select mode: block the host's R/U so the chooser is not swapped away under the cursor; other
            // global keys still act on the hovered option, as the view-mode popup has always allowed
            InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(code);
            if (CalcKeys.isShowRecipe(key) || CalcKeys.isShowUses(key)) event.setCanceled(true);
            return;
        }
        ItemStack stack = hoveredStack();
        if (stack.isEmpty()) return;
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(code);
        if (CalcKeys.isShowRecipe(key) || CalcKeys.isShowUses(key)) {
            close();
            BookmarkPanel.showRecipes(stack, CalcKeys.isShowRecipe(key) ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        if (!open()) return;
        if (selecting == null ? !RecipeHearts.isRecipesScreen(event.getScreen()) : event.getScreen() != host) {
            close();
            return;
        }
        hover = indexAt(event.getMouseX(), event.getMouseY());
        GuiGraphicsExtractor g = event.getGuiGraphics();
        g.nextStratum();
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.identity();
        try {
            draw(g, event.getMouseX(), event.getMouseY());
        } finally {
            pose.popMatrix();
        }
    }

    private static void draw(GuiGraphicsExtractor g, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        int w = width();
        int h = height();
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER);
        g.fill(x, y, x + w, y + h, BACKGROUND);
        String title = items.size() + " accepted items";
        g.text(font, title, x + PAD, y + PAD, 0xFFE0E0E0, true);
        String chosen = selecting == null ? null : selecting.itemId();
        String saved = selecting == null ? null : TagDefaults.defaultFor(selecting.options);
        for (int row = 0; row < rows(); row++) {
            for (int col = 0; col < COLS; col++) {
                int index = (scroll + row) * COLS + col;
                if (index >= items.size()) break;
                int cx = x + PAD + col * CELL;
                int cy = y + PAD + TITLE + row * CELL;
                ItemStack stack = items.get(index);
                if (index == hover) g.fill(cx, cy, cx + CELL, cy + CELL, HIGHLIGHT);
                FluidStacks.item(g, stack, cx + 1, cy + 1);
                g.itemDecorations(font, stack, cx + 1, cy + 1, null);
                if (selecting != null) {
                    // the item that case uses now gets a frame, the tag's saved default a small "D" (#39)
                    String id = optionIds.get(index);
                    if (id.equals(chosen)) {
                        g.fill(cx, cy, cx + CELL, cy + 1, MARK);
                        g.fill(cx, cy + CELL - 1, cx + CELL, cy + CELL, MARK);
                        g.fill(cx, cy + 1, cx + 1, cy + CELL - 1, MARK);
                        g.fill(cx + CELL - 1, cy + 1, cx + CELL, cy + CELL, MARK);
                    }
                    if (id.equals(saved)) g.text(font, "D", cx + CELL - 6, cy + CELL - 8, MARK, true);
                }
            }
        }
        if (hover >= 0) tooltip(g, font, items.get(hover), mx, my);
    }

    private static void tooltip(GuiGraphicsExtractor g, Font font, ItemStack stack, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        List<Component> tip = new ArrayList<>(stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(mc.level), mc.player, net.minecraft.world.item.TooltipFlag.Default.NORMAL));
        if (tip.isEmpty()) tip.add(FluidStacks.name(stack));
        tip.add(Component.literal(selecting == null
            ? "§7Click: recipes, right click: uses"
            : "§7Click: use for this slot; Ctrl: use and keep as the default"));
        HotkeyTips.addModName(tip, stack);
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : tip) lines.addAll(font.split(line, 260));
        g.setTooltipForNextFrame(font, lines, mx, my);
    }
}
