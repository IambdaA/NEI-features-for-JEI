package neiaddon;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix3x2fStack;
import org.lwjgl.glfw.GLFW;

/**
 * NEI's item zoom: the item under the mouse drawn large in the free space beside the inventory (the side the mouse is
 * not on), with its name and the key that toggles it. Sizes and side rules are NEI's: the item fills
 * {@code itemZoomAmount / 1000} of the free width, at least twice its normal size.
 */
@EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT)
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

    /** Width of the container screen's own area: its left edge and width, or null when they cannot be read. */
    private static int[] bounds(Screen s) {
        if (boundsFailed) return null;
        try {
            for (Method m : s.getClass()
                .getMethods()) {
                if (m.getName()
                    .equals("getRectangle") && m.getParameterCount() == 0 && m.getReturnType() == Rect2i.class) {
                    Rect2i r = (Rect2i) m.invoke(s);
                    return new int[] { r.getX(), r.getWidth() };
                }
            }
            int left = -1;
            int width = -1;
            for (Class<?> c = s.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != int.class) continue;
                    if (f.getName()
                        .equals("leftPos")) {
                        f.setAccessible(true);
                        left = f.getInt(s);
                    } else if (f.getName()
                        .equals("imageWidth")) {
                            f.setAccessible(true);
                            width = f.getInt(s);
                        }
                }
            }
            if (left >= 0 && width > 0) return new int[] { left, width };
        } catch (ReflectiveOperationException | RuntimeException e) {
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
        if (Minecraft.getInstance()
            .hasShiftDown() && TOGGLE.isActiveAndMatches(key)) {
            AddonConfig.ZOOM_ENABLED.set(!AddonConfig.ZOOM_ENABLED.get());
            AddonConfig.ZOOM_ENABLED.save();
            event.setCanceled(true);
            return;
        }
        if (HOLD.isActiveAndMatches(key)) holdDown = true;
        boolean in = ZOOM_IN.isActiveAndMatches(key);
        if ((in || ZOOM_OUT.isActiveAndMatches(key)) && !CalcKeys.hoveredStack(s, AddonConfig.ZOOM_OVERLAYS_ONLY.get())
            .isEmpty()) {
            int[] b = bounds(s);
            if (b != null) changeZoom(s, b, in ? 1 : -1);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (HOLD.isActiveAndMatches(InputConstants.Type.KEYSYM.getOrCreate(event.getKeyCode()))) holdDown = false;
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
     * JEI draws its item list after the screen's foreground, so the zoom listens to the event that comes after it
     * (ScreenEvent.Render.Post), last of all; where that event does not exist it falls back to the foreground one.
     * Looked up by name so either spelling compiles.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void install() {
        for (String name : new String[] { "net.neoforged.neoforge.client.event.ScreenEvent$Render$Post",
            "net.neoforged.neoforge.client.event.ScreenEvent$Render$Foreground" }) {
            try {
                Class type = Class.forName(name);
                NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, type, (Consumer) e -> draw((ScreenEvent.Render) e));
                NeiAddon.LOGGER.info("Item zoom draws on {}", name);
                return;
            } catch (ClassNotFoundException ignored) {}
        }
        NeiAddon.LOGGER.warn("Item zoom: no screen render event found, it will not be drawn");
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
        GuiGraphicsExtractor g = event.getGuiGraphics();
        g.nextStratum();
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.identity();
        try {
            pose.translate(x, y);
            pose.scale(scale, scale);
            g.fakeItem(stack, 0, 0);
            pose.identity();
            float centerX = x + size / 2;
            int textY = (int) (y + size) + 10;
            if (AddonConfig.ZOOM_NAME.get()) {
                String name = font.plainSubstrByWidth(stack.getHoverName()
                    .getString(), avail);
                g.text(font, name, (int) (centerX - font.width(name) / 2f), textY, 0xFF000000 | AddonConfig.ZOOM_NAME_COLOR.get(), true);
                textY += font.lineHeight;
            }
            if (AddonConfig.ZOOM_ENABLED.get() && AddonConfig.ZOOM_HELP.get()) {
                String[] lines = { "Item Zoom", "Shift + " + TOGGLE.getTranslatedKeyMessage()
                    .getString() + " toggles" };
                textY += 10;
                for (String line : lines) {
                    String cropped = font.plainSubstrByWidth(line, avail);
                    g.text(font, cropped, (int) (centerX - font.width(cropped) / 2f), textY, 0xFF777777, false);
                    textY += font.lineHeight;
                }
            }
        } finally {
            pose.popMatrix();
        }
    }
}
