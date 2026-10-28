package neiaddon.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.Slot;
import neiaddon.platform.ClientPlatform;
import net.neoforged.neoforge.network.PacketDistributor;

/** NeoForge's answers to the shared client code's questions. */
final class NeoClientPlatform implements ClientPlatform {

    @Override
    public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(type);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public int[] containerBounds(AbstractContainerScreen<?> screen) {
        return new int[] { screen.getGuiLeft(), screen.getGuiTop(), screen.getXSize(), screen.getYSize() };
    }

    @Override
    public Slot hoveredSlot(AbstractContainerScreen<?> screen) {
        return screen.getSlotUnderMouse();
    }
}
