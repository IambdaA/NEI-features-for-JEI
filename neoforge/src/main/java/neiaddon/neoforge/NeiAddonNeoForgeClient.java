package neiaddon.neoforge;

import neiaddon.CalcKeys;
import neiaddon.HighlightTips;
import neiaddon.NeiAddon;
import neiaddon.NeiSettings;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** NeoForge client entry point: key mappings, the highlight tip layer, the event hooks and the Mods list settings button. */
@Mod(value = NeiAddon.MODID, dist = Dist.CLIENT)
public final class NeiAddonNeoForgeClient {

    public NeiAddonNeoForgeClient(IEventBus modBus, ModContainer container) {
        NeiAddon.initClient(new NeoClientPlatform());
        modBus.addListener(NeiAddonNeoForgeClient::registerKeys);
        modBus.addListener(NeiAddonNeoForgeClient::registerLayers);
        NeoEvents.install();
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> NeiSettings.create(parent));
    }

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        CalcKeys.all()
            .forEach(event::register);
    }

    private static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CROSSHAIR, HighlightTips.LAYER_ID, HighlightTips.layer());
    }
}
