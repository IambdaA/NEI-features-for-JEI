package neiaddon;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** The client events that fire on the mod event bus. (1.21.1 needs them apart from the game bus handlers.) */
@EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
final class ClientModEvents {

    private ClientModEvents() {}

    @SubscribeEvent
    static void registerKeys(RegisterKeyMappingsEvent event) {
        CalcKeys.register(event);
    }

    @SubscribeEvent
    static void registerLayers(RegisterGuiLayersEvent event) {
        HighlightTips.register(event);
    }
}
