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
import net.minecraft.client.gui.LayeredDraw;

/** Fabric client entry point: key mappings, the highlight tip layer, the channel's client half and the event hooks. */
public final class NeiAddonFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NeiAddon.initClient(new FabricClientPlatform());
        CalcKeys.all()
            .forEach(KeyBindingHelper::registerKeyBinding);
        ClientPlayNetworking.registerGlobalReceiver(Share.SharedPayload.TYPE, (payload, context) -> ShareClient.keep(payload.id(), payload.payload()));
        ClientPlayNetworking.registerGlobalReceiver(Share.ImportRequest.TYPE, (payload, context) -> ShareClient.open(payload.id()));
        LayeredDraw.Layer tips = HighlightTips.layer();
        HudRenderCallback.EVENT.register((graphics, deltaTracker) -> {
            if (!Minecraft.getInstance().options.hideGui) tips.render(graphics, deltaTracker);
        });
        FabricEvents.install();
    }
}
