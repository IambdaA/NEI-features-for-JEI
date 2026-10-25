package neiaddon;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** The common events that fire on the mod event bus (it loads on a dedicated server too). */
@Mod.EventBusSubscriber(modid = NeiAddon.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
final class CommonModEvents {

    private CommonModEvents() {}

    @SubscribeEvent
    static void setup(FMLCommonSetupEvent event) {
        Share.register();
    }
}
