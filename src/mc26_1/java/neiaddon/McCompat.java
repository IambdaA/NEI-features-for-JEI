package neiaddon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;

/** The bits of the client API that differ between Minecraft versions. This is the 26.1.x copy (src/mc26_1). */
final class McCompat {

    private McCompat() {}

    static Screen screen() {
        return Minecraft.getInstance().screen;
    }

    static void setScreen(Screen screen) {
        Minecraft.getInstance().setScreen(screen);
    }

    static ChatComponent chat() {
        return Minecraft.getInstance().gui.getChat();
    }

    /** A file-name safe id of the world or server being played, or null in menus. */
    static String worldKey() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.getSingleplayerServer() != null) {
                return "sp-" + mc.getSingleplayerServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .normalize()
                    .getFileName();
            }
            if (mc.getCurrentServer() != null) return "mp-" + mc.getCurrentServer().ip;
        } catch (RuntimeException ignored) {}
        return null;
    }

    static int guiTicks() {
        return Minecraft.getInstance().gui.getGuiTicks();
    }
}
