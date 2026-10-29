package neiaddon.fabric.mixin;

import neiaddon.McCompat;
import neiaddon.fabric.FabricEventsAccess;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric has no screen event for typed characters: the addon's text fields get them from here. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void neiaddon$charTyped(long window, CharacterEvent event, CallbackInfo ci) {
        Screen screen = McCompat.screen();
        if (screen == null) return;
        if (FabricEventsAccess.charTyped(screen, event.codepoint(), 0)) ci.cancel();
    }
}
