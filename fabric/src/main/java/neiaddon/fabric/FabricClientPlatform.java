package neiaddon.fabric;

import neiaddon.fabric.mixin.AbstractContainerScreenAccessor;
import neiaddon.platform.ClientPlatform;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.Slot;

/** Fabric's answers to the shared client code's questions. */
final class FabricClientPlatform implements ClientPlatform {

    @Override
    public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public int[] containerBounds(AbstractContainerScreen<?> screen) {
        AbstractContainerScreenAccessor a = (AbstractContainerScreenAccessor) screen;
        return new int[] { a.neiaddon$leftPos(), a.neiaddon$topPos(), a.neiaddon$imageWidth(), a.neiaddon$imageHeight() };
    }

    @Override
    public Slot hoveredSlot(AbstractContainerScreen<?> screen) {
        return ((AbstractContainerScreenAccessor) screen).neiaddon$hoveredSlot();
    }
}
