package neiaddon.forge;

import java.util.Optional;
import neiaddon.Craft;
import neiaddon.CraftServer;
import neiaddon.NeiAddon;
import neiaddon.Share;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** The chat link channel on Forge: the shared messages ({@link Share}) on a SimpleChannel. */
final class ForgeNetwork {

    private static final String PROTOCOL = "1";
    /** Accepts any version (and a missing channel), so the channel is optional on either side. */
    static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(NeiAddon.MODID, "main"), () -> PROTOCOL, v -> true, v -> true);

    /**
     * Server-side autocrafting ({@link Craft}) on a channel of its own: a server with an older addon has the main
     * channel but no handler for these messages. Accepts a missing channel on either side too.
     */
    static final SimpleChannel CRAFT = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(NeiAddon.MODID, "craft"), () -> PROTOCOL, v -> true, v -> true);

    /**
     * Whole-chain autocrafting ({@link Craft.CraftChainRequest}) on a channel of its own: a server without it (even one
     * with the craft channel) reports it missing, and the client crafts one recipe per request there.
     */
    static final SimpleChannel CRAFT_CHAIN = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(NeiAddon.MODID, "craft_chain"), () -> PROTOCOL, v -> true, v -> true);

    private ForgeNetwork() {}

    static void register() {
        CHANNEL.registerMessage(0, Share.ShareRequest.class, Share.ShareRequest::encode, Share.ShareRequest::decode, (packet, ctx) -> {
            NetworkEvent.Context c = ctx.get();
            ServerPlayer sender = c.getSender();
            c.enqueueWork(() -> {
                if (sender != null) Share.broadcast(sender, packet.payload());
            });
            c.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, Share.SharedPayload.class, Share.SharedPayload::encode, Share.SharedPayload::decode, (packet, ctx) -> {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> neiaddon.ShareClient.keep(packet.id(), packet.payload()));
            c.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, Share.ImportRequest.class, Share.ImportRequest::encode, Share.ImportRequest::decode, (packet, ctx) -> {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> neiaddon.ShareClient.open(packet.id()));
            c.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CRAFT.registerMessage(0, Craft.CraftRequest.class, Craft.CraftRequest::encode, Craft.CraftRequest::decode,
            (packet, ctx) -> {
                NetworkEvent.Context c = ctx.get();
                ServerPlayer sender = c.getSender();
                c.enqueueWork(() -> {
                    if (sender != null) CraftServer.handle(sender, packet);
                });
                c.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CRAFT.registerMessage(1, Craft.CraftResult.class, Craft.CraftResult::encode, Craft.CraftResult::decode,
            (packet, ctx) -> {
                NetworkEvent.Context c = ctx.get();
                c.enqueueWork(() -> neiaddon.AutoCraft.serverCraftResult(packet));
                c.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CRAFT_CHAIN.registerMessage(0, Craft.CraftChainRequest.class, Craft.CraftChainRequest::encode,
            Craft.CraftChainRequest::decode, (packet, ctx) -> {
                NetworkEvent.Context c = ctx.get();
                ServerPlayer sender = c.getSender();
                c.enqueueWork(() -> {
                    if (sender != null) CraftServer.handleChain(sender, packet);
                });
                c.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CRAFT_CHAIN.registerMessage(1, Craft.CraftChainResult.class, Craft.CraftChainResult::encode,
            Craft.CraftChainResult::decode, (packet, ctx) -> {
                NetworkEvent.Context c = ctx.get();
                c.enqueueWork(() -> neiaddon.AutoCraft.serverChainResult(packet));
                c.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
}
