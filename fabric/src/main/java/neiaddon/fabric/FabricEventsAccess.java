package neiaddon.fabric;

import net.minecraft.client.gui.screens.Screen;

/** The two hooks the mixins call (the mixin package cannot see the package-private {@link FabricEvents}). */
public final class FabricEventsAccess {

    private FabricEventsAccess() {}

    public static boolean charTyped(Screen screen, char c, int modifiers) {
        return FabricEvents.charTyped(screen, c, modifiers);
    }

    public static void opening(Screen current, Screen next) {
        FabricEvents.opening(current, next);
    }
}
