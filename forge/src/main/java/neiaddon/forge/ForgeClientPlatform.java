package neiaddon.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.inventory.Slot;
import neiaddon.platform.ClientPlatform;

/** Forge's answers to the shared client code's questions. */
final class ForgeClientPlatform implements ClientPlatform {

    @Override
    public boolean canSendToServer() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && ForgeNetwork.CHANNEL.isRemotePresent(connection.getConnection());
    }

    @Override
    public void sendToServer(Object message) {
        ForgeNetwork.CHANNEL.sendToServer(message);
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
