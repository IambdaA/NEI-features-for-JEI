package neiaddon.fabric.mixin;

import neiaddon.fabric.FabricEventsAccess;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric has no event for "the frame was drawn": the crafting tree picture reads the frame back from here. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(
        method = "runTick(Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V",
            shift = At.Shift.AFTER))
    private void neiaddon$frameDone(boolean advanceGameTime, CallbackInfo ci) {
        FabricEventsAccess.frameDone();
    }
}
