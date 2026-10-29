package neiaddon.fabric;

import java.nio.file.Path;
import java.util.Optional;
import neiaddon.platform.Platform;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

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
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) ServerPlayNetworking.send(player, payload);
    }
}
