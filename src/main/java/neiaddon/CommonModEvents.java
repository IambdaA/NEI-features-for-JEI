package neiaddon;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** The common events that fire on the mod event bus (it loads on a dedicated server too). */
@EventBusSubscriber(modid = NeiAddon.MODID, bus = EventBusSubscriber.Bus.MOD)
final class CommonModEvents {

    private CommonModEvents() {}

    @SubscribeEvent
    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        Share.register(event);
    }
}
