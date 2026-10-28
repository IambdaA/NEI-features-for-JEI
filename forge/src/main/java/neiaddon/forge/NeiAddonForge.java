package neiaddon.forge;

import neiaddon.NeiAddon;
import neiaddon.Share;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Forge entry point: the chat link channel and its command (both sides), then the client part. */
@Mod(NeiAddon.MODID)
public final class NeiAddonForge {

    public NeiAddonForge() {
        NeiAddon.init(new ForgePlatform());
        IEventBus modBus = FMLJavaModLoadingContext.get()
            .getModEventBus();
        modBus.addListener((FMLCommonSetupEvent event) -> ForgeNetwork.register());
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher()
            .register(Share.command()));
        if (FMLEnvironment.dist == Dist.CLIENT) ForgeClient.init(modBus);
    }
}
