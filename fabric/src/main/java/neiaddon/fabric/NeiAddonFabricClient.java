package neiaddon.fabric;

import neiaddon.CalcKeys;
import neiaddon.HighlightTips;
import neiaddon.NeiAddon;
import neiaddon.Share;
import neiaddon.ShareClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;

/** Fabric client entry point: key mappings, the highlight tip layer, the channel's client half and the event hooks. */
public final class NeiAddonFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        NeiAddon.initClient(new FabricClientPlatform());
        KeyMapping.Category.register(CalcKeys.CATEGORY.id());
        CalcKeys.all()
            .forEach(KeyMappingHelper::registerKeyMapping);
        ClientPlayNetworking.registerGlobalReceiver(Share.SharedPayload.TYPE, (payload, context) -> ShareClient.keep(payload.id(), payload.payload()));
        ClientPlayNetworking.registerGlobalReceiver(Share.ImportRequest.TYPE, (payload, context) -> ShareClient.open(payload.id()));
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, HighlightTips.LAYER_ID, HighlightTips::render);
        FabricEvents.install();
    }
}
