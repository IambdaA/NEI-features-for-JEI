package neiaddon.fabric.mixin;

import java.util.List;
import neiaddon.fabric.FabricEventsAccess;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Where NeoForge fires RenderTooltipEvent.Pre: before anything of a tooltip is drawn (deferred and direct ones). */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {

    @Inject(
        method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
        at = @At("HEAD"))
    private void neiaddon$tooltip(Font font, List<ClientTooltipComponent> lines, int xo, int yo, ClientTooltipPositioner positioner,
        Identifier style, CallbackInfo ci) {
        FabricEventsAccess.tooltip((GuiGraphicsExtractor) (Object) this);
    }
}
