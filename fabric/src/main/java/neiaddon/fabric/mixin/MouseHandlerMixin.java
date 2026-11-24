package neiaddon.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import neiaddon.event.Bus;
import neiaddon.event.InputEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric has no event for a raw mouse press: the addon reads the presses here, at the head of the mouse handler, before the
 * screen's own handling (EMI's item list takes its presses inside it). The same event as Forge's raw press (see
 * {@link InputEvent.MouseButtonPre}); only presses, and only while a screen is open.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void neiaddon$onPress(long window, int button, int action, int mods, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (action != GLFW.GLFW_PRESS || mc.screen == null || window != mc.getWindow().getWindow()) return;
        InputEvent.MouseButtonPre event = new InputEvent.MouseButtonPre(button);
        Bus.post(event);
        if (event.isCanceled()) ci.cancel();
    }
}
