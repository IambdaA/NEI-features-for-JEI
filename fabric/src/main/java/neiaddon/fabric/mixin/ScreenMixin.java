package neiaddon.fabric.mixin;

import neiaddon.fabric.FabricEventsAccess;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where NeoForge fires its Render.Foreground (screens that are not container screens) and Render.Post events. The same
 * injection points JEI uses for its own overlay; the higher priority runs this after JEI's mixin.
 */
@Mixin(value = Screen.class, priority = 1500)
public abstract class ScreenMixin {

    @Inject(
        method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
            ordinal = 0,
            shift = At.Shift.AFTER))
    private void neiaddon$foreground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        Screen screen = (Screen) (Object) this;
        if (screen instanceof AbstractContainerScreen<?>) return;
        FabricEventsAccess.foreground(screen, graphics, mouseX, mouseY, a);
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V", at = @At("TAIL"))
    private void neiaddon$post(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        FabricEventsAccess.post((Screen) (Object) this, graphics, mouseX, mouseY, a);
    }
}
