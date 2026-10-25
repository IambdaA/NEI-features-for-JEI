package neiaddon;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * NEI-style extras that sit next to Just Enough Items. Almost all of it is client side; only chat links ({@link Share})
 * also run on a server, and that channel is optional.
 */
@Mod(NeiAddon.MODID)
public class NeiAddon {

    public static final String MODID = "neiaddon";
    public static final Logger LOGGER = LogUtils.getLogger();

    public NeiAddon() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, AddonConfig.SPEC);
    }
}
