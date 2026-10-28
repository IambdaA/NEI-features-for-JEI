package neiaddon.neoforge;

import java.util.function.BiConsumer;
import java.util.function.Function;
import neiaddon.event.Bus;
import neiaddon.event.ClientChatReceivedEvent;
import neiaddon.event.ClientTickEvent;
import neiaddon.event.ItemTooltipEvent;
import neiaddon.event.RenderFrameEvent;
import neiaddon.event.ScreenEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Hooks NeoForge's events to the addon's own bus. One NeoForge listener per priority that has handlers, so the order
 * against other mods' listeners (JEI's) is NeoForge's own.
 */
final class NeoEvents {

    private NeoEvents() {}

    private static EventPriority priority(neiaddon.event.EventPriority p) {
        return switch (p) {
            case HIGHEST -> EventPriority.HIGHEST;
            case HIGH -> EventPriority.HIGH;
            case NORMAL -> EventPriority.NORMAL;
            case LOW -> EventPriority.LOW;
            case LOWEST -> EventPriority.LOWEST;
        };
    }

    /** Listens to {@code nativeType} once per priority that has handlers for {@code ours}; {@code back} carries a cancel over. */
    private static <N extends net.neoforged.bus.api.Event, E extends neiaddon.event.Event> void bridge(Class<N> nativeType, Class<E> ours,
        Function<N, E> wrap, BiConsumer<N, E> back) {
        for (neiaddon.event.EventPriority p : neiaddon.event.EventPriority.values()) {
            if (!Bus.hasHandlers(ours, p)) continue;
            NeoForge.EVENT_BUS.addListener(priority(p), false, nativeType, (N event) -> {
                E mine = wrap.apply(event);
                Bus.post(mine, p);
                back.accept(event, mine);
            });
        }
    }

    static void install() {
        bridge(
            net.neoforged.neoforge.client.event.ScreenEvent.Render.Post.class,
            ScreenEvent.Render.Post.class,
            e -> new ScreenEvent.Render.Post(e.getScreen(), e.getGuiGraphics(), e.getMouseX(), e.getMouseY(), e.getPartialTick()),
            (n, m) -> {});
        bridge(
            net.neoforged.neoforge.client.event.ScreenEvent.MouseButtonPressed.Pre.class,
            ScreenEvent.MouseButtonPressed.Pre.class,
            e -> new ScreenEvent.MouseButtonPressed.Pre(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getButton()),
            (n, m) -> {
                if (m.isCanceled()) n.setCanceled(true);
            });
        bridge(
            net.neoforged.neoforge.client.event.ScreenEvent.MouseButtonReleased.Pre.class,
            ScreenEvent.MouseButtonReleased.Pre.class,
            e -> new ScreenEvent.MouseButtonReleased.Pre(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getButton()),
            (n, m) -> {
                if (m.isCanceled()) n.setCanceled(true);
            });
        bridge(
            net.neoforged.neoforge.client.event.ScreenEvent.MouseDragged.Pre.class,
            ScreenEvent.MouseDragged.Pre.class,
            e -> new ScreenEvent.MouseDragged.Pre(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getMouseButton(), e.getDragX(), e.getDragY()),
            (n, m) -> {
                if (m.isCanceled()) n.setCanceled(true);
            });
        bridge(
            net.neoforged.neoforge.client.event.ScreenEvent.MouseScrolled.Pre.class,
            ScreenEvent.MouseScrolled.Pre.class,
            e -> new ScreenEvent.MouseScrolled.Pre(e.getScreen(), e.getMouseX(), e.getMouseY(), e.getScrollDeltaX(), e.getScrollDeltaY()),
            (n, m) -> {
                if (m.isCanceled()) n.setCanceled(true);
            });
        bridge(
            net.neoforged.neoforge.client.event.ScreenEvent.KeyPressed.Pre.class,
            ScreenEvent.KeyPressed.Pre.class,
            e -> new ScreenEvent.KeyPressed.Pre(e.getScreen(), e.getKeyCode(), e.getScanCode(), e.getModifiers()),
            (n, m) -> {
                if (m.isCanceled()) n.setCanceled(true);
            });
        bridge(
            net.neoforged.neoforge.client.event.ScreenEvent.KeyReleased.Pre.class,
            ScreenEvent.KeyReleased.Pre.class,
            e -> new ScreenEvent.KeyReleased.Pre(e.getScreen(), e.getKeyCode(), e.getScanCode(), e.getModifiers()),
            (n, m) -> {
                if (m.isCanceled()) n.setCanceled(true);
            });
        bridge(
            net.neoforged.neoforge.client.event.ScreenEvent.CharacterTyped.Pre.class,
            ScreenEvent.CharacterTyped.Pre.class,
            e -> new ScreenEvent.CharacterTyped.Pre(e.getScreen(), e.getCodePoint(), e.getModifiers()),
            (n, m) -> {
                if (m.isCanceled()) n.setCanceled(true);
            });
        bridge(
            net.neoforged.neoforge.client.event.ScreenEvent.Opening.class,
            ScreenEvent.Opening.class,
            e -> new ScreenEvent.Opening(e.getCurrentScreen(), e.getNewScreen()),
            (n, m) -> {});
        bridge(
            net.neoforged.neoforge.client.event.ClientTickEvent.Post.class,
            ClientTickEvent.Post.class,
            e -> new ClientTickEvent.Post(),
            (n, m) -> {});
        bridge(
            net.neoforged.neoforge.client.event.RenderFrameEvent.Post.class,
            RenderFrameEvent.Post.class,
            e -> new RenderFrameEvent.Post(),
            (n, m) -> {});
        bridge(
            net.neoforged.neoforge.client.event.ClientChatReceivedEvent.class,
            ClientChatReceivedEvent.class,
            e -> new ClientChatReceivedEvent(e.getMessage()),
            (n, m) -> {});
        bridge(
            net.neoforged.neoforge.event.entity.player.ItemTooltipEvent.class,
            ItemTooltipEvent.class,
            e -> new ItemTooltipEvent(e.getItemStack(), e.getToolTip()),
            (n, m) -> {});
    }
}
