package neiaddon;
import net.minecraft.world.item.ItemStack;
/** Stub for the plain-Java checks: one worldless session, plain copy-with-count. */
final class McCompat {
    static String worldKey() {
        return null;
    }
    static ItemStack copyWithCount(ItemStack stack, int count) {
        return stack.copyWithCount(count);
    }
}
