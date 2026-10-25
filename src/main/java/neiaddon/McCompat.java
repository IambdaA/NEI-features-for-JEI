package neiaddon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/** The bits of the client API that differ between Minecraft versions. This is the 1.20.1 copy. */
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

    static ItemStack copyWithCount(ItemStack stack, int count) {
        ItemStack copy = stack.copy();
        copy.setCount(count);
        return copy;
    }
}
