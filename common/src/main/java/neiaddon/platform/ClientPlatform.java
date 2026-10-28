package neiaddon.platform;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.Slot;

/** The client-only part of what the shared code needs from the loader. */
public interface ClientPlatform {

    Holder HOLDER = new Holder();

    static ClientPlatform get() {
        ClientPlatform p = HOLDER.platform;
        if (p == null) throw new IllegalStateException("The NEI addon's client entry point has not run yet");
        return p;
    }

    /** Whether the connected server accepts this payload (the channel is optional). */
    boolean canSendToServer(CustomPacketPayload.Type<?> type);

    void sendToServer(CustomPacketPayload payload);

    /** {left, top, width, height} of the container's own area. */
    int[] containerBounds(AbstractContainerScreen<?> screen);

    /** The slot under the mouse, or null. */
    Slot hoveredSlot(AbstractContainerScreen<?> screen);

    final class Holder {

        private volatile ClientPlatform platform;

        public void set(ClientPlatform p) {
            platform = p;
        }
    }
}
