package neiaddon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;

/** The bits of the client API that differ between Minecraft versions. This is the 1.21.1 copy. */
final class McCompat {

    private McCompat() {}

    static Screen screen() {
        return Minecraft.getInstance().screen;
    }

    static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    static boolean shift() {
        return Screen.hasShiftDown();
    }

    static boolean ctrl() {
        return Screen.hasControlDown();
    }

    static boolean alt() {
        return Screen.hasAltDown();
    }

    static ChatComponent chat() {
        return Minecraft.getInstance().gui.getChat();
    }

    static int guiTicks() {
        return Minecraft.getInstance().gui.getGuiTicks();
    }
}
