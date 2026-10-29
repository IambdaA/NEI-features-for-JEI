package neiaddon.neoforge;

import neiaddon.NeiAddon;
import neiaddon.Share;
import neiaddon.ShareClient;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** NeoForge entry point, both sides: the chat link channel and its command (the rest is client only). */
@Mod(NeiAddon.MODID)
public final class NeiAddonNeoForge {

    public NeiAddonNeoForge(IEventBus modBus) {
        NeiAddon.init(new NeoPlatform());
        modBus.addListener(NeiAddonNeoForge::registerPayloads);
        NeoForge.EVENT_BUS.addListener(NeiAddonNeoForge::registerCommands);
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher()
            .register(Share.command());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
            .executesOn(HandlerThread.MAIN)
            .optional()
            .playToServer(
                Share.ShareRequest.TYPE,
                Share.ShareRequest.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> Share.broadcast((ServerPlayer) context.player(), packet.payload())))
            .playToClient(
                Share.SharedPayload.TYPE,
                Share.SharedPayload.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> ShareClient.keep(packet.id(), packet.payload())))
            .playToClient(
                Share.ImportRequest.TYPE,
                Share.ImportRequest.STREAM_CODEC,
                (packet, context) -> context.enqueueWork(() -> ShareClient.open(packet.id())));
    }
}
