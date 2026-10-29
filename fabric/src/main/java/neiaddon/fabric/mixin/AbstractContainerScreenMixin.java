package neiaddon.fabric.mixin;

import neiaddon.fabric.FabricEventsAccess;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Where NeoForge fires Render.Foreground for container screens: after the contents, before the carried item and tooltip. */
@Mixin(value = AbstractContainerScreen.class, priority = 1500)
public abstract class AbstractContainerScreenMixin {

    @Inject(method = "extractCarriedItem(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V", at = @At("HEAD"))
    private void neiaddon$foreground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo ci) {
        FabricEventsAccess.foreground((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY, 0F);
    }
}
