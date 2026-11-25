package neiaddon.fabric;

import neiaddon.Craft;
import neiaddon.CraftServer;
import neiaddon.NeiAddon;
import neiaddon.Share;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Fabric entry point, both sides: the chat link channel and its command (the rest is client only). */
public final class NeiAddonFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        NeiAddon.init(new FabricPlatform());
        ServerPlayNetworking.registerGlobalReceiver(Share.SHARE_REQUEST, (server, player, handler, buf, responseSender) -> {
            Share.ShareRequest request = Share.ShareRequest.decode(buf);
            server.execute(() -> Share.broadcast(player, request.payload()));
        });
        ServerPlayNetworking.registerGlobalReceiver(Craft.CRAFT_REQUEST,
            (server, player, handler, buf, responseSender) -> {
                Craft.CraftRequest request = Craft.CraftRequest.decode(buf);
                server.execute(() -> CraftServer.handle(player, request));
            });
        ServerPlayNetworking.registerGlobalReceiver(Craft.CHAIN_REQUEST,
            (server, player, handler, buf, responseSender) -> {
                Craft.CraftChainRequest request = Craft.CraftChainRequest.decode(buf);
                server.execute(() -> CraftServer.handleChain(player, request));
            });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(Share.command()));
    }
}
