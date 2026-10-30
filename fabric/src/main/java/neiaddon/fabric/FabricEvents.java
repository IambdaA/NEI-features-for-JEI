package neiaddon.fabric;

import java.util.Map;
import java.util.WeakHashMap;
import neiaddon.McCompat;
import neiaddon.NeiAddon;
import neiaddon.event.Bus;
import neiaddon.event.ClientChatReceivedEvent;
import neiaddon.event.ClientTickEvent;
import neiaddon.event.EventPriority;
import neiaddon.event.ItemTooltipEvent;
import neiaddon.event.RenderFrameEvent;
import neiaddon.event.ScreenEvent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * Hooks Fabric's events to the addon's own bus. Fabric's input events belong to one screen, so they are registered when
 * a screen is set up; one listener per priority that has handlers, in phases ordered like NeoForge's priorities (JEI's listeners are in the
 * default phase, between NORMAL and LOW). Fabric has no typed-character event, no foreground or
 * after-the-screen draw event and no frame event: those come from mixins ({@code KeyboardHandlerMixin},
 * {@code ScreenMixin}, {@code AbstractContainerScreenMixin}, {@code MinecraftMixin}). A screen opening or closing is
 * noticed on the next tick.
 */
final class FabricEvents {

    private static final Identifier TRACK = id("track");
    /**
     * One phase per priority. Fabric's default phase is where JEI registers its own screen listeners (before ours, as it
     * does it earlier), so NORMAL gets a phase of its own just BEFORE the default one: like on NeoForge, the addon's
     * normal handlers see a key or click before JEI does.
     */
    private static final Identifier[] PHASE = { id("highest"), id("high"), id("normal"), id("low"), id("lowest") };

    /** The newest listener set of every screen: older ones (a screen is set up again when it is resized) stand down. */
    private static final Map<Screen, Object> LIVE = new WeakHashMap<>();

    private static Screen lastScreen;

    private FabricEvents() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NeiAddon.MODID, path);
    }

    private static boolean live(Screen screen, Object token) {
        return LIVE.get(screen) == token;
    }

    private static void order(Event<?> event) {
        event.addPhaseOrdering(TRACK, PHASE[0]);
        for (int i = 0; i + 1 < PHASE.length; i++) event.addPhaseOrdering(PHASE[i], PHASE[i + 1]);
        event.addPhaseOrdering(PHASE[2], Event.DEFAULT_PHASE);
        event.addPhaseOrdering(Event.DEFAULT_PHASE, PHASE[3]);
    }

    static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Screen now = McCompat.screen();
            if (now != lastScreen) {
                Screen before = lastScreen;
                lastScreen = now;
                Bus.post(new ScreenEvent.Opening(before, now));
            }
            Bus.post(new ClientTickEvent.Post());
        });
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> Bus.post(new ItemTooltipEvent(stack, lines)));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> Bus.post(new ClientChatReceivedEvent(message)));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> hook(screen));
    }

    // ---- called by the mixins

    /** A typed character; true when a handler used it. */
    static boolean charTyped(Screen screen, int codePoint, int modifiers) {
        ScreenEvent.CharacterTyped.Pre event = new ScreenEvent.CharacterTyped.Pre(screen, codePoint, modifiers);
        Bus.post(event);
        return event.isCanceled();
    }

    /** After the screen's main contents, before its tooltips (NeoForge's Render.Foreground). */
    static void foreground(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Bus.post(new ScreenEvent.Render.Foreground(screen, graphics, mouseX, mouseY, partialTick));
    }

    /** After the whole screen was drawn (NeoForge's Render.Post). */
    static void post(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Bus.post(new ScreenEvent.Render.Post(screen, graphics, mouseX, mouseY, partialTick));
    }

    /** After the frame was drawn. */
    static void frameDone() {
        Bus.post(new RenderFrameEvent.Post());
    }

    // ---- per screen input

    private static void hook(Screen screen) {
        Object token = new Object();
        LIVE.put(screen, token);

        var click = ScreenMouseEvents.allowMouseClick(screen);
        order(click);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.MouseButtonPressed.Pre.class, p)) continue;
            click.register(PHASE[p.ordinal()], (s, event) -> {
                if (!live(s, token)) return true;
                ScreenEvent.MouseButtonPressed.Pre mine = new ScreenEvent.MouseButtonPressed.Pre(s, event.x(), event.y(), event.button());
                Bus.post(mine, p);
                return !mine.isCanceled();
            });
        }

        var release = ScreenMouseEvents.allowMouseRelease(screen);
        order(release);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.MouseButtonReleased.Pre.class, p)) continue;
            release.register(PHASE[p.ordinal()], (s, event) -> {
                if (!live(s, token)) return true;
                ScreenEvent.MouseButtonReleased.Pre mine = new ScreenEvent.MouseButtonReleased.Pre(s, event.x(), event.y(), event.button());
                Bus.post(mine, p);
                return !mine.isCanceled();
            });
        }

        var drag = ScreenMouseEvents.allowMouseDrag(screen);
        order(drag);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.MouseDragged.Pre.class, p)) continue;
            drag.register(PHASE[p.ordinal()], (s, event, dragX, dragY) -> {
                if (!live(s, token)) return true;
                ScreenEvent.MouseDragged.Pre mine = new ScreenEvent.MouseDragged.Pre(s, event.x(), event.y(), event.button(), dragX, dragY);
                Bus.post(mine, p);
                return !mine.isCanceled();
            });
        }

        var scroll = ScreenMouseEvents.allowMouseScroll(screen);
        order(scroll);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.MouseScrolled.Pre.class, p)) continue;
            scroll.register(PHASE[p.ordinal()], (s, x, y, horizontal, vertical) -> {
                if (!live(s, token)) return true;
                ScreenEvent.MouseScrolled.Pre mine = new ScreenEvent.MouseScrolled.Pre(s, x, y, horizontal, vertical);
                Bus.post(mine, p);
                return !mine.isCanceled();
            });
        }

        var press = ScreenKeyboardEvents.allowKeyPress(screen);
        order(press);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.KeyPressed.Pre.class, p)) continue;
            press.register(PHASE[p.ordinal()], (s, event) -> {
                if (!live(s, token)) return true;
                ScreenEvent.KeyPressed.Pre mine = new ScreenEvent.KeyPressed.Pre(s, event.key(), event.scancode(), event.modifiers());
                Bus.post(mine, p);
                return !mine.isCanceled();
            });
        }

        var keyUp = ScreenKeyboardEvents.allowKeyRelease(screen);
        order(keyUp);
        for (EventPriority p : EventPriority.values()) {
            if (!Bus.hasHandlers(ScreenEvent.KeyReleased.Pre.class, p)) continue;
            keyUp.register(PHASE[p.ordinal()], (s, event) -> {
                if (!live(s, token)) return true;
                ScreenEvent.KeyReleased.Pre mine = new ScreenEvent.KeyReleased.Pre(s, event.key(), event.scancode(), event.modifiers());
                Bus.post(mine, p);
                return !mine.isCanceled();
            });
        }
    }
}
