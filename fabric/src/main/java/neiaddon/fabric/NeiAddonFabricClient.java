package neiaddon.fabric;

import neiaddon.CalcKeys;
import neiaddon.HighlightTips;
import neiaddon.NeiAddon;
import neiaddon.Share;
import neiaddon.ShareClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;

/** Fabric client entry point: key mappings, the highlight tip, the channel's client half and the event hooks. */
public final class NeiAddonFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NeiAddon.initClient(new FabricClientPlatform());
        CalcKeys.all()
            .forEach(KeyBindingHelper::registerKeyBinding);
        ClientPlayNetworking.registerGlobalReceiver(Share.SHARED_PAYLOAD, (client, handler, buf, responseSender) -> {
            Share.SharedPayload payload = Share.SharedPayload.decode(buf);
            client.execute(() -> ShareClient.keep(payload.id(), payload.payload()));
        });
        ClientPlayNetworking.registerGlobalReceiver(Share.IMPORT_REQUEST, (client, handler, buf, responseSender) -> {
            Share.ImportRequest request = Share.ImportRequest.decode(buf);
            client.execute(() -> ShareClient.open(request.id()));
        });
        HighlightTips tips = HighlightTips.layer();
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
            if (!Minecraft.getInstance().options.hideGui) tips.render(graphics, tickDelta);
        });
        FabricEvents.install();
    }
}
