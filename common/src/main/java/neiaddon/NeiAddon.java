package neiaddon;

import com.mojang.logging.LogUtils;
import neiaddon.event.Bus;
import neiaddon.platform.ClientPlatform;
import neiaddon.platform.Platform;
import org.slf4j.Logger;

/**
 * NEI-style extras that sit next to Just Enough Items. Almost all of it is client side; only chat links ({@link Share})
 * also run on a server, and that channel is optional. The loader modules call {@link #init} (both sides) and
 * {@link #initClient} (client) from their entry points.
 */
public final class NeiAddon {

    public static final String MODID = "neiaddon";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** True once the client side is set up (the settings button waits for it). */
    static boolean clientReady;

    private NeiAddon() {}

    public static void init(Platform platform) {
        Platform.HOLDER.set(platform);
        neiaddon.chain.ChainMath.containerOps = new ContainerRules();
    }

    /** Registers the client event handlers; the loader then hooks its own events to the bus (see {@link Bus}). */
    public static void initClient(ClientPlatform platform) {
        ClientPlatform.HOLDER.set(platform);
        for (Class<?> handlers : new Class<?>[] { AutoCraft.class, BookmarkPanel.class, CalcKeys.class, ColorPickerScreen.class,
            ConfigButton.class, CraftablesPanel.class, HotkeyTips.class, ItemZoom.class, JeiSettings.class, NeiSettings.class,
            QuantityField.class, RecipeFilter.class, RecipeHearts.class, ShareClient.class, TreeRecipes.class, TreeShot.class }) {
            Bus.register(handlers);
        }
        clientReady = true;
    }
}
