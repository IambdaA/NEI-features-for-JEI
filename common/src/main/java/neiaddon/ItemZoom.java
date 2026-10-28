package neiaddon;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ClientTickEvent;
import neiaddon.event.ScreenEvent;
import neiaddon.platform.ClientPlatform;
import org.lwjgl.glfw.GLFW;

/**
 * NEI's item zoom: the item under the mouse drawn large in the free space beside the inventory (the side the mouse is
 * not on), with its name and the key that toggles it. Sizes and side rules are NEI's: the item fills
 * {@code itemZoomAmount / 1000} of the free width, at least twice its normal size.
 */
public final class ItemZoom {

    private static final int SLOT = 16;
    private static final int MIN_SCALE = 2;
    private static final int MAX_ZOOM = 1000;

    /** NEI: itemzoom.toggle = Z with Shift. */
    public static final KeyMapping TOGGLE = new KeyMapping("key.neiaddon.itemzoom_toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CalcKeys.CATEGORY);
    public static final KeyMapping HOLD = new KeyMapping("key.neiaddon.itemzoom_hold", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CalcKeys.CATEGORY);
    public static final KeyMapping ZOOM_IN = new KeyMapping("key.neiaddon.itemzoom_in", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CalcKeys.CATEGORY);
    public static final KeyMapping ZOOM_OUT = new KeyMapping("key.neiaddon.itemzoom_out", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, CalcKeys.CATEGORY);

    private static boolean holdDown;
    private static boolean boundsFailed;

    private ItemZoom() {}

    // ---------------------------------------------------------------- the inventory's size (for the free side space)

    /** The container screen's own area: its left edge and width, or null when they cannot be read. */
    private static int[] bounds(Screen s) {
        if (boundsFailed) return null;
        try {
            if (s instanceof AbstractContainerScreen<?> c) {
                int[] r = ClientPlatform.get().containerBounds(c);
                if (r != null && r[0] >= 0 && r[2] > 0) return new int[] { r[0], r[2] };
            }
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Item zoom: could not read the container's size", e);
        }
        boundsFailed = true;
        return null;
    }

    // ---------------------------------------------------------------- keys

    private static boolean active(Screen s) {
        return s instanceof AbstractContainerScreen<?> && !boundsFailed;
    }

    @SubscribeEvent
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        Screen s = event.getScreen();
        if (!active(s) || CalcKeys.typing()) return;
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(event.getKeyCode());
        if (McCompat.shift() && McCompat.matches(TOGGLE, key)) {
            AddonConfig.ZOOM_ENABLED.set(!AddonConfig.ZOOM_ENABLED.get());
            AddonConfig.ZOOM_ENABLED.save();
            event.setCanceled(true);
            return;
        }
        if (McCompat.matches(HOLD, key)) holdDown = true;
        boolean in = McCompat.matches(ZOOM_IN, key);
        if ((in || McCompat.matches(ZOOM_OUT, key)) && !CalcKeys.hoveredStack(s, AddonConfig.ZOOM_OVERLAYS_ONLY.get())
            .isEmpty()) {
            int[] b = bounds(s);
            if (b != null) changeZoom(s, b, in ? 1 : -1);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (McCompat.matches(HOLD, InputConstants.Type.KEYSYM.getOrCreate(event.getKeyCode()))) holdDown = false;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (holdDown && !(McCompat.screen() instanceof AbstractContainerScreen<?>)) holdDown = false;
    }

    // ---------------------------------------------------------------- size and side

    private static float pointSize(Screen s, int[] b) {
        return Math.max(0, s.width - b[1]) / 2f / MAX_ZOOM;
    }

    private static int zoomAmount(float pointSize) {
        return Math.max(Math.round(SLOT * MIN_SCALE / pointSize), AddonConfig.ZOOM_AMOUNT.get());
    }

    private static void changeZoom(Screen s, int[] b, int direction) {
        float point = pointSize(s, b);
        if (point <= 0) return;
        int scale = Math.round(zoomAmount(point) * point / SLOT);
        int next = direction > 0 ? Math.min(MAX_ZOOM, Math.round((scale + 1) / (point / SLOT)))
            : Math.max(0, Math.round(Math.max(MIN_SCALE, scale - 1) / (point / SLOT)));
        AddonConfig.ZOOM_AMOUNT.set(next);
        AddonConfig.ZOOM_AMOUNT.save();
    }

    /** Auto: NEI's choice when the mouse is on neither side, the side with fewer items; the JEI list always has more. */
    private static boolean autoPrefersLeft() {
        int left = BookmarkPanel.isHidden() ? 0 : Bookmarks.page().entries.size();
        boolean listShown = Calculator.runtime != null && Calculator.runtime.getIngredientListOverlay()
            .isListDisplayed();
        return left <= (listShown ? Integer.MAX_VALUE : 0);
    }

    private static boolean solid(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock()
            .defaultBlockState()
            .canOcclude();
    }

    // ---------------------------------------------------------------- drawing

    /**
     * JEI draws its item list after the screen's foreground, so the zoom draws on the event that comes after it
     * (ScreenEvent.Render.Post), last of all.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        draw(event);
    }

    private static void draw(ScreenEvent.Render event) {
        Screen s = event.getScreen();
        if (!active(s) || !(AddonConfig.ZOOM_ENABLED.get() || holdDown)) return;
        ItemStack stack = CalcKeys.hoveredStack(s, AddonConfig.ZOOM_OVERLAYS_ONLY.get());
        if (stack.isEmpty() || AddonConfig.ZOOM_ONLY_SOLID.get() && !solid(stack)) return;
        int[] b = bounds(s);
        if (b == null) return;
        int avail = (s.width - b[1]) / 2;
        if (avail < SLOT * 2) return;

        float point = pointSize(s, b);
        int scale = Math.round(zoomAmount(point) * point / SLOT);
        float size = SLOT * scale;
        float y = (s.height - size) / 2f;
        float x = (avail - size) / 2f;
        int mouseX = event.getMouseX();
        boolean leftFree = avail < mouseX;
        boolean rightFree = mouseX < s.width - avail;
        // Left and Right are fixed; Auto keeps to the side the mouse is not on
        int side = AddonConfig.ZOOM_SIDE.get();
        boolean right = side == 1 ? false : side == 2 ? true : leftFree && rightFree ? !autoPrefersLeft() : !leftFree;
        if (right) x += s.width - avail;

        Font font = Minecraft.getInstance().font;
        GuiGraphics g = event.getGuiGraphics();
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.identity();
        pose.translateZ(Pose.OVER);
        try {
            pose.translate(x, y);
            pose.scale(scale, scale);
            g.renderFakeItem(stack, 0, 0);
            pose.identity();
            float centerX = x + size / 2;
            int textY = (int) (y + size) + 10;
            if (AddonConfig.ZOOM_NAME.get()) {
                String name = font.plainSubstrByWidth(stack.getHoverName()
                    .getString(), avail);
                g.drawString(font, name, (int) (centerX - font.width(name) / 2f), textY, 0xFF000000 | AddonConfig.ZOOM_NAME_COLOR.get(), true);
                textY += font.lineHeight;
            }
            if (AddonConfig.ZOOM_ENABLED.get() && AddonConfig.ZOOM_HELP.get()) {
                String[] lines = { "Item Zoom", "Shift + " + TOGGLE.getTranslatedKeyMessage()
                    .getString() + " toggles" };
                textY += 10;
                for (String line : lines) {
                    String cropped = font.plainSubstrByWidth(line, avail);
                    g.drawString(font, cropped, (int) (centerX - font.width(cropped) / 2f), textY, 0xFF777777, false);
                    textY += font.lineHeight;
                }
            }
        } finally {
            pose.popMatrix();
        }
    }
}
