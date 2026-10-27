package neiaddon;

import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;

/** Client-only part of the mod: the item zoom's draw hook and the settings screen the Mods list opens. */
final class NeiAddonClient {

    /** True once the client part has been set up (the settings button needs it). */
    static boolean ready;

    private NeiAddonClient() {}

    static void init() {
        ItemZoom.install();
        ModLoadingContext.get()
            .registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> NeiSettings.create(parent)));
        ready = true;
    }
}
