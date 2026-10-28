package neiaddon.forge;

import java.util.Optional;
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
    }
}
