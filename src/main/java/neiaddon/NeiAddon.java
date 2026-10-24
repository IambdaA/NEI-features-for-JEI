package neiaddon;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * NEI-style extras that sit next to Just Enough Items. Almost all of it is client side; only chat links ({@link Share})
 * also run on a server, and that channel is optional.
 */
@Mod(NeiAddon.MODID)
public class NeiAddon {

    public static final String MODID = "neiaddon";
    public static final Logger LOGGER = LogUtils.getLogger();

    public NeiAddon(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, AddonConfig.SPEC);
    }
}
