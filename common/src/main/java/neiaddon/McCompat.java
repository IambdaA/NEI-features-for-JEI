package neiaddon;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
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

    /**
     * Whether the key pressed is the one this mapping is bound to (NeoForge's isActiveAndMatches, without its conflict
     * contexts and modifiers, which these mappings do not use; vanilla's own check works the same on every loader).
     */
    static boolean matches(KeyMapping mapping, InputConstants.Key key) {
        return key.getType() == InputConstants.Type.KEYSYM && mapping.matches(key.getValue(), InputConstants.UNKNOWN.getValue());
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
