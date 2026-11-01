package neiaddon.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Map;
import java.util.Set;
import neiaddon.CalcKeys;
import neiaddon.NeiAddon;
import neiaddon.fabric.mixin.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Vanilla lets one key mapping own a key: when the addon's A (add bookmark) is the owner, the game's own A (move left)
 * gets no key presses. The addon only reads its keys in screens, so on every tick the ownership goes back to the game's
 * mapping, wherever one shares the key. (NeoForge and Forge give the addon's keys a screen-only conflict context instead.)
 */
final class KeyMapFix {

    private static boolean failed;

    private KeyMapFix() {}

    static void tick() {
        if (failed) return;
        try {
            Map<InputConstants.Key, KeyMapping> map = KeyMappingAccessor.neiaddon$map();
            Set<KeyMapping> ours = Set.copyOf(CalcKeys.all());
            KeyMapping[] all = Minecraft.getInstance().options.keyMappings;
            for (KeyMapping mine : ours) {
                String name = mine.saveString();
                InputConstants.Key key = InputConstants.getKey(name);
                if (key == InputConstants.UNKNOWN || map.get(key) != mine) continue;
                for (KeyMapping other : all) {
                    if (!ours.contains(other) && other.saveString()
                        .equals(name)) {
                        map.put(key, other);
                        break;
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            NeiAddon.LOGGER.warn("Could not keep the addon's keys off the game's key table", e);
        }
    }
}
