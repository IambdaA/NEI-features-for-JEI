package neiaddon.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;

/** The hooks the mixins call (the mixin package cannot see the package-private {@link FabricEvents}). */
public final class FabricEventsAccess {

    private FabricEventsAccess() {}

    public static boolean charTyped(Screen screen, int codePoint, int modifiers) {
        return FabricEvents.charTyped(screen, codePoint, modifiers);
    }

    public static void foreground(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        FabricEvents.foreground(screen, graphics, mouseX, mouseY, partialTick);
    }

    public static void post(Screen screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        FabricEvents.post(screen, graphics, mouseX, mouseY, partialTick);
    }

    public static void frameDone() {
        FabricEvents.frameDone();
    }
}
