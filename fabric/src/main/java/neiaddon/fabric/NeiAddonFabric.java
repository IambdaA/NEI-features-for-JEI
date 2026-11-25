package neiaddon.fabric;

import neiaddon.Craft;
import neiaddon.CraftServer;
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
        PayloadTypeRegistry.playC2S()
            .register(Share.ShareRequest.TYPE, Share.ShareRequest.STREAM_CODEC);
        PayloadTypeRegistry.playS2C()
            .register(Share.SharedPayload.TYPE, Share.SharedPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C()
            .register(Share.ImportRequest.TYPE, Share.ImportRequest.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Share.ShareRequest.TYPE, (payload, context) -> Share.broadcast(context.player(), payload.payload()));
        PayloadTypeRegistry.playC2S()
            .register(Craft.CraftRequest.TYPE, Craft.CraftRequest.STREAM_CODEC);
        PayloadTypeRegistry.playS2C()
            .register(Craft.CraftResult.TYPE, Craft.CraftResult.STREAM_CODEC);
        PayloadTypeRegistry.playC2S()
            .register(Craft.CraftChainRequest.TYPE, Craft.CraftChainRequest.STREAM_CODEC);
        PayloadTypeRegistry.playS2C()
            .register(Craft.CraftChainResult.TYPE, Craft.CraftChainResult.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Craft.CraftRequest.TYPE,
            (payload, context) -> CraftServer.handle(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(Craft.CraftChainRequest.TYPE,
            (payload, context) -> CraftServer.handleChain(context.player(), payload));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(Share.command()));
    }
}
