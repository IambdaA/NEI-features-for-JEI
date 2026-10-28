package neiaddon.fabric;

import java.util.Map;
import java.util.WeakHashMap;
import neiaddon.NeiAddon;
import neiaddon.event.Bus;
import neiaddon.event.ClientChatReceivedEvent;
import neiaddon.event.EventPriority;
import neiaddon.event.ItemTooltipEvent;
import neiaddon.event.ScreenEvent;
import neiaddon.event.TickEvent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/**
 * Hooks Fabric's events to the addon's own bus. Fabric's screen events belong to one screen, so they are registered
 * when a screen is set up; one listener per priority that has handlers, in phases ordered like NeoForge's priorities
 * (the default phase is NORMAL, which is where JEI's listeners are). Fabric has no drag or typed-character screen
 * event: dragging is read from the mouse each frame and typing comes from {@code KeyboardHandlerMixin}. The screen
 * opening comes from {@code MinecraftMixin}.
 */
final class FabricEvents {

    private static final ResourceLocation TRACK = id("track");
    private static final ResourceLocation FRAME = id("frame");
    private static final ResourceLocation[] PHASE = { id("highest"), id("high"), Event.DEFAULT_PHASE, id("low"), id("lowest") };

    /** The newest listener set of every screen: older ones (a screen is set up again when it is resized) stand down. */
    private static final Map<Screen, Object> LIVE = new WeakHashMap<>();

    private static int heldButton = -1;
    private static Screen heldScreen;
    private static double lastX;
    private static double lastY;

    private FabricEvents() {}

    private static ResourceLocation id(String path) {
        return new ResourceLocation(NeiAddon.MODID, path);
    }

    private static boolean live(Screen screen, Object token) {
        return LIVE.get(screen) == token;
    }

    private static void order(Event<?> event) {
        event.addPhaseOrdering(TRACK, PHASE[0]);
        for (int i = 0; i + 1 < PHASE.length; i++) event.addPhaseOrdering(PHASE[i], PHASE[i + 1]);
        event.addPhaseOrdering(PHASE[4], FRAME);
    }

    static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> Bus.post(new TickEvent.ClientTickEvent(TickEvent.Phase.END)));
        ItemTooltipCallback.EVENT.register((stack, flag, lines) -> Bus.post(new ItemTooltipEvent(stack, lines)));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> Bus.post(new ClientChatReceivedEvent(message)));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> hook(screen));
    }

    /** Called by the typed-character mixin; true when a handler used the character. */
    static boolean charTyped(Screen screen, char c, int modifiers) {
        ScreenEvent.CharacterTyped.Pre event = new ScreenEvent.CharacterTyped.Pre(screen, c, modifiers);
        Bus.post(event);
        return event.isCanceled();
    }

    /** Called by the screen mixin before a screen is set (the new one is null when the screen closes). */
    static void opening(Screen current, Screen next) {
        Bus.post(new ScreenEvent.Opening(current, next));
    }

    private static void hook(Screen screen) {
        Object token = new Object();
        LIVE.put(screen, token);

        Event<ScreenMouseEvents.AllowMouseClick> click = ScreenMouseEvents.allowMouseClick(screen);
        order(click);
        click.register(TRACK, (s, x, y, button) -> {
            if (live(s, token)) {
                heldButton = button;
                heldScreen = s;
                lastX = x;
                lastY = y;
            }
            return true;
        });
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.MouseButtonPressed.Pre.class, p)) continue;
            click.register(PHASE[p.ordinal()], (s, x, y, button) -> {
                if (!live(s, token)) return true;
                ScreenEvent.MouseButtonPressed.Pre event = new ScreenEvent.MouseButtonPressed.Pre(s, x, y, button);
                Bus.post(event, p);
                return !event.isCanceled();
            });
        }

        Event<ScreenMouseEvents.AllowMouseRelease> release = ScreenMouseEvents.allowMouseRelease(screen);
        order(release);
        release.register(TRACK, (s, x, y, button) -> {
            if (live(s, token) && button == heldButton) heldButton = -1;
            return true;
        });
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.MouseButtonReleased.Pre.class, p)) continue;
            release.register(PHASE[p.ordinal()], (s, x, y, button) -> {
                if (!live(s, token)) return true;
                ScreenEvent.MouseButtonReleased.Pre event = new ScreenEvent.MouseButtonReleased.Pre(s, x, y, button);
                Bus.post(event, p);
                return !event.isCanceled();
            });
        }

        Event<ScreenMouseEvents.AllowMouseScroll> scroll = ScreenMouseEvents.allowMouseScroll(screen);
        order(scroll);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.MouseScrolled.Pre.class, p)) continue;
            scroll.register(PHASE[p.ordinal()], (s, x, y, horizontal, vertical) -> {
                if (!live(s, token)) return true;
                ScreenEvent.MouseScrolled.Pre event = new ScreenEvent.MouseScrolled.Pre(s, x, y, vertical);
                Bus.post(event, p);
                return !event.isCanceled();
            });
        }

        Event<ScreenKeyboardEvents.AllowKeyPress> press = ScreenKeyboardEvents.allowKeyPress(screen);
        order(press);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.KeyPressed.Pre.class, p)) continue;
            press.register(PHASE[p.ordinal()], (s, key, scancode, modifiers) -> {
                if (!live(s, token)) return true;
                ScreenEvent.KeyPressed.Pre event = new ScreenEvent.KeyPressed.Pre(s, key, scancode, modifiers);
                Bus.post(event, p);
                return !event.isCanceled();
            });
        }

        Event<ScreenKeyboardEvents.AllowKeyRelease> keyUp = ScreenKeyboardEvents.allowKeyRelease(screen);
        order(keyUp);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.KeyReleased.Pre.class, p)) continue;
            keyUp.register(PHASE[p.ordinal()], (s, key, scancode, modifiers) -> {
                if (!live(s, token)) return true;
                ScreenEvent.KeyReleased.Pre event = new ScreenEvent.KeyReleased.Pre(s, key, scancode, modifiers);
                Bus.post(event, p);
                return !event.isCanceled();
            });
        }

        if (Bus.hasHandlers(ScreenEvent.MouseDragged.Pre.class)) {
            ScreenEvents.beforeRender(screen)
                .register((s, graphics, mouseX, mouseY, delta) -> {
                    if (live(s, token)) drag(s);
                });
        }

        Event<ScreenEvents.AfterRender> render = ScreenEvents.afterRender(screen);
        order(render);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.Render.Post.class, p)) continue;
            render.register(PHASE[p.ordinal()], (s, graphics, mouseX, mouseY, delta) -> {
                if (live(s, token)) Bus.post(new ScreenEvent.Render.Post(s, graphics, mouseX, mouseY, delta), p);
            });
        }
        if (Bus.hasHandlers(TickEvent.RenderTickEvent.class)) {
            // what is drawn must have reached the frame buffer when the crafting tree picture is taken
            render.register(FRAME, (s, graphics, mouseX, mouseY, delta) -> {
                if (!live(s, token)) return;
                graphics.flush();
                Bus.post(new TickEvent.RenderTickEvent(TickEvent.Phase.END));
            });
        }
    }

    /** Fabric has no screen drag event: while a button pressed on the screen is held, the mouse is followed each frame. */
    private static void drag(Screen screen) {
        if (heldButton < 0 || heldScreen != screen) return;
        Minecraft mc = Minecraft.getInstance();
        if (GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), heldButton) != GLFW.GLFW_PRESS) {
            heldButton = -1;
            return;
        }
        double x = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double y = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        if (x == lastX && y == lastY) return;
        double dx = x - lastX;
        double dy = y - lastY;
        lastX = x;
        lastY = y;
        Bus.post(new ScreenEvent.MouseDragged.Pre(screen, x, y, heldButton, dx, dy));
    }
}
