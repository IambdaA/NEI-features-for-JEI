package neiaddon.fabric.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's key to mapping table, in which one mapping owns a key (the others on it get no key presses). */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("MAP")
    static Map<InputConstants.Key, KeyMapping> neiaddon$map() {
        throw new AssertionError();
    }
}
