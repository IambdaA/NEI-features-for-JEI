package neiaddon.fabric.mixin;

import neiaddon.fabric.FabricEventsAccess;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric has no screen event for typed characters: the addon's text fields get them from here. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void neiaddon$charTyped(long window, int codePoint, int modifiers, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        Screen screen = mc.screen;
        if (screen == null || window != mc.getWindow().getWindow() || mc.getOverlay() != null) return;
        if (!Character.isBmpCodePoint(codePoint)) return;
        if (FabricEventsAccess.charTyped(screen, (char) codePoint, modifiers)) ci.cancel();
    }
}
