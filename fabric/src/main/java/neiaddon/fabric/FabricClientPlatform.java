package neiaddon.fabric;

import neiaddon.fabric.mixin.AbstractContainerScreenAccessor;
import neiaddon.platform.ClientPlatform;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import neiaddon.Share;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.inventory.Slot;

/** Fabric's answers to the shared client code's questions. */
final class FabricClientPlatform implements ClientPlatform {

    @Override
    public boolean canSendToServer() {
        return ClientPlayNetworking.canSend(Share.SHARE_REQUEST);
    }

    @Override
    public void sendToServer(Object message) {
        if (!(message instanceof Share.ShareRequest request)) throw new IllegalArgumentException("Not a message to the server: " + message);
        FriendlyByteBuf buf = PacketByteBufs.create();
        Share.ShareRequest.encode(request, buf);
        ClientPlayNetworking.send(Share.SHARE_REQUEST, buf);
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

    @Override
    public long fluidAmount(Object jeiFluidIngredient) {
        return jeiFluidIngredient instanceof mezz.jei.api.fabric.ingredients.fluids.IJeiFluidIngredient fluid ? fluid.getAmount() : 0L;
    }
}
