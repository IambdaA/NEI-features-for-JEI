package neiaddon;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.ItemTooltipEvent;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;

/**
 * NEI's HeldItemHandler: in creative mode or cheat mode, with an item on the cursor over JEI's item list or the bookmark
 * panel, a left click deletes the item and a right click takes one off it; the tooltip says so. JEI does this itself
 * over its list when its own cheat mode is on, so this only steps in where JEI would not.
 */
public final class HeldItemDelete {

    private HeldItemDelete() {}

    private static AbstractContainerMenu menuOf(Screen s) {
        Minecraft mc = Minecraft.getInstance();
        if (s instanceof AbstractContainerScreen<?> c) return c.getMenu();
        return mc.player == null ? null : mc.player.containerMenu;
    }

    /** Is a click at the point a delete? */
    static boolean active(Screen s, double mx, double my) {
        if (s == null || RecipeHearts.isRecipesScreen(s) || Calculator.runtime == null) return false;
        AbstractContainerMenu menu = menuOf(s);
        if (menu == null || menu.getCarried()
            .isEmpty() || !BookmarkPanel.canGive()) return false;
        if (BookmarkPanel.contains(s, mx, my)) return true;
        Rect2i list = JeiAreas.list();
        boolean overList = list != null && Calculator.runtime.getIngredientListOverlay()
            .isListDisplayed() && mx >= list.getX() && mx < list.getX() + list.getWidth() && my >= list.getY() && my < list.getY() + list.getHeight();
        // JEI's own cheat mode already deletes over its list
        return overList && !JeiGive.jeiCheating();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        int button = event.getButton();
        Screen s = event.getScreen();
        if (button != 0 && button != 1 || !active(s, event.getMouseX(), event.getMouseY())) return;
        AbstractContainerMenu menu = menuOf(s);
        ItemStack held = menu.getCarried();
        boolean done;
        if (s instanceof CreativeModeInventoryScreen) {
            // the creative screen keeps the cursor on this side (the game's own delete slot does the same)
            done = true;
        } else if (button == 1 && held.getCount() > 1) {
            done = JeiGive.deleteHeld(held) && JeiGive.giveToMouse(held, held.getCount() - 1);
        } else {
            done = JeiGive.deleteHeld(held);
        }
        if (!done) return;
        // what the server will do anyway, shown right away
        if (button == 1 && held.getCount() > 1) held.shrink(1);
        else menu.setCarried(ItemStack.EMPTY);
        event.setCanceled(true);
    }

    /** True while the delete prompt is what the mouse position shows (set every frame before JEI draws its tooltips). */
    private static boolean prompting;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onRenderStart(ScreenEvent.Render.Pre event) {
        prompting = active(event.getScreen(), event.getMouseX(), event.getMouseY());
    }

    /**
     * JEI draws the tooltip of the list item under the mouse before the prompt below does. Empty its lines (the prompt
     * is drawn over what JEI still adds, the mod name) so only the prompt shows.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onTooltip(ItemTooltipEvent event) {
        if (!prompting || HotkeyTips.building || StackIds.reading()) return;
        java.util.Optional<ItemStack> hovered = HotkeyTips.hoveredInList();
        if (hovered.isPresent() && hovered.get()
            .getItem() == event.getItemStack()
                .getItem()) {
            event.getToolTip()
                .clear();
        }
    }

    /** True while the prompt itself is drawn: only other tooltips are held back. */
    private static boolean drawingPrompt;

    /**
     * Should a tooltip about to be drawn be dropped? While the delete prompt shows over JEI's list, JEI still draws the
     * tooltip of the ingredient under the mouse (fluids and other non-items too); the loaders' tooltip hooks ask here.
     */
    public static boolean hideTooltip() {
        if (drawingPrompt) return false;
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        if (s == null) return false;
        double mx = mc.mouseHandler.xpos() * mc.getWindow()
            .getGuiScaledWidth() / mc.getWindow()
                .getScreenWidth();
        double my = mc.mouseHandler.ypos() * mc.getWindow()
            .getGuiScaledHeight() / mc.getWindow()
                .getScreenHeight();
        try {
            return active(s, mx, my);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        Screen s = event.getScreen();
        int mx = event.getMouseX();
        int my = event.getMouseY();
        if (!active(s, mx, my)) return;
        Minecraft mc = Minecraft.getInstance();
        List<FormattedCharSequence> lines = List.of(Component.literal("§cClick to delete")
            .getVisualOrderText(),
            Component.literal("§7Right click: remove one")
                .getVisualOrderText());
        drawingPrompt = true;
        try {
            event.getGuiGraphics().renderTooltip(mc.font, lines, mx, my);
        } finally {
            drawingPrompt = false;
        }
    }
}
