package neiaddon.fabric;

import neiaddon.NeiAddon;
import neiaddon.Share;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Fabric entry point, both sides: the chat link channel and its command (the rest is client only). */
public final class NeiAddonFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NeiAddon.init(new FabricPlatform());
        PayloadTypeRegistry.serverboundPlay()
            .register(Share.ShareRequest.TYPE, Share.ShareRequest.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay()
            .register(Share.SharedPayload.TYPE, Share.SharedPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay()
            .register(Share.ImportRequest.TYPE, Share.ImportRequest.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Share.ShareRequest.TYPE, (payload, context) -> Share.broadcast(context.player(), payload.payload()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(Share.command()));
    }
}
