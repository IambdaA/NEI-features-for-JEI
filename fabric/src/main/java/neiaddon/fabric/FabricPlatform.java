package neiaddon.fabric;

import java.nio.file.Path;
import java.util.Optional;
import neiaddon.platform.Platform;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import neiaddon.Share;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Fabric's answers to the shared code's questions (both sides). */
final class FabricPlatform implements Platform {

    @Override
    public Path configDir() {
        return FabricLoader.getInstance()
            .getConfigDir();
    }

    @Override
    public Optional<String> modDisplayName(String modId) {
        return FabricLoader.getInstance()
            .getModContainer(modId)
            .map(container -> container.getMetadata()
                .getName());
    }

    @Override
    public ItemStack craftingRemainder(ItemStack stack) {
        Item item = stack.getItem();
        return item.hasCraftingRemainingItem() ? new ItemStack(item.getCraftingRemainingItem()) : ItemStack.EMPTY;
    }

    @Override
    public void sendToPlayer(ServerPlayer player, Object message) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        ResourceLocation id;
        if (message instanceof Share.SharedPayload m) {
            id = Share.SHARED_PAYLOAD;
            Share.SharedPayload.encode(m, buf);
        } else if (message instanceof Share.ImportRequest m) {
            id = Share.IMPORT_REQUEST;
            Share.ImportRequest.encode(m, buf);
        } else {
            throw new IllegalArgumentException("Not a message to a client: " + message);
        }
        if (ServerPlayNetworking.canSend(player, id)) ServerPlayNetworking.send(player, id, buf);
    }
}
