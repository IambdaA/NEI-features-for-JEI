package neiaddon;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only part of the mod: the config screen NeoForge builds from {@link AddonConfig} (Mods list and our button). */
@Mod(value = NeiAddon.MODID, dist = Dist.CLIENT)
public class NeiAddonClient {

    static ModContainer container;

    public NeiAddonClient(ModContainer container) {
        NeiAddonClient.container = container;
        ItemZoom.install();
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
