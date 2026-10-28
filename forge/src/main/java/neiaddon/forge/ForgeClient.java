package neiaddon.forge;

import neiaddon.CalcKeys;
import neiaddon.HighlightTips;
import neiaddon.NeiAddon;
import neiaddon.NeiSettings;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;

/** Forge client side: key mappings, the highlight tip overlay, the event hooks and the Mods list settings button. */
final class ForgeClient {

    private ForgeClient() {}

    static void init(IEventBus modBus) {
        NeiAddon.initClient(new ForgeClientPlatform());
        modBus.addListener((RegisterKeyMappingsEvent event) -> CalcKeys.all()
            .forEach(event::register));
        modBus.addListener((RegisterGuiOverlaysEvent event) -> {
            HighlightTips tips = HighlightTips.layer();
            event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), HighlightTips.LAYER_ID, (gui, graphics, partialTick, width, height) -> tips.render(graphics, partialTick));
        });
        ForgeEvents.install();
        ModLoadingContext.get()
            .registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> NeiSettings.create(parent)));
    }
}
