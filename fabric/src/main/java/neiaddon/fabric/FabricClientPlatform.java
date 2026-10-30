package neiaddon.fabric;

import neiaddon.platform.ClientPlatform;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.inventory.Slot;

/** Fabric's answers to the shared client code's questions (the fields are opened up by neiaddon.accesswidener). */
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
        return new int[] { screen.leftPos, screen.topPos, screen.imageWidth, screen.imageHeight };
    }

    @Override
    public Slot hoveredSlot(AbstractContainerScreen<?> screen) {
        return screen.hoveredSlot;
    }

    @Override
    public long fluidAmount(Object jeiFluidIngredient) {
        return jeiFluidIngredient instanceof mezz.jei.api.fabric.ingredients.fluids.IJeiFluidIngredient fluid ? fluid.getAmount() : 0L;
    }
}
