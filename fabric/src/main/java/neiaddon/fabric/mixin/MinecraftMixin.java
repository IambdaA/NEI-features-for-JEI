package neiaddon.fabric.mixin;

import neiaddon.fabric.FabricEventsAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric has no event for a screen opening or closing: the addon's "a new screen is coming" hook comes from here. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void neiaddon$setScreen(Screen next, CallbackInfo ci) {
        FabricEventsAccess.opening(((Minecraft) (Object) this).screen, next);
    }
}
