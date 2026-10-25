package neiaddon;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;

/** The client events that fire on the mod event bus. (1.21.1 needs them apart from the game bus handlers.) */
@Mod.EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
final class ClientModEvents {

    private ClientModEvents() {}

    @SubscribeEvent
    static void registerKeys(RegisterKeyMappingsEvent event) {
        CalcKeys.register(event);
    }

    @SubscribeEvent
    static void registerLayers(RegisterGuiOverlaysEvent event) {
        HighlightTips.register(event);
    }
}
