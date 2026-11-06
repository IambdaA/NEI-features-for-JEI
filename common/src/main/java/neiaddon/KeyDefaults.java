package neiaddon;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import neiaddon.event.TickEvent;
import neiaddon.event.SubscribeEvent;

/**
 * Once, the first time this version runs: puts the addon's keys that have a default but are unbound back on that default. A
 * key can be left unbound in the game's options file by an older build or a reset (Add Bookmark, A, showed as "Not bound" on
 * a Fabric instance, so A did nothing over a bookmark). After this one pass the keys are never touched again, so a key
 * unbound on purpose stays unbound.
 */
public final class KeyDefaults {

    private static final int VERSION = 1;

    private KeyDefaults() {}

    @SubscribeEvent
    static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (AddonConfig.KEY_DEFAULTS.get() >= VERSION) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        boolean changed = false;
        for (KeyMapping key : CalcKeys.all()) {
            if (key.isUnbound() && !key.getDefaultKey()
                .equals(com.mojang.blaze3d.platform.InputConstants.UNKNOWN)) {
                NeiAddon.LOGGER.info("Key {} was unbound, set back to its default {}", key.getName(), key.getDefaultKey()
                    .getName());
                key.setKey(key.getDefaultKey());
                changed = true;
            }
        }
        if (changed) {
            KeyMapping.resetMapping();
            mc.options.save();
        }
        AddonConfig.KEY_DEFAULTS.set(VERSION);
        AddonConfig.KEY_DEFAULTS.save();
    }
}
