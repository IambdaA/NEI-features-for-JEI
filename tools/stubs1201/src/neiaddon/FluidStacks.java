package neiaddon;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
/** Stub for the plain-Java checks: only plain items exist, every id without a registered item is empty. */
public final class FluidStacks {
    public static ItemStack stackOf(String id) {
        Item item = id == null ? null : Item.REGISTRY.get(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
    public static String itemIdOf(ItemStack stack) {
        return null;
    }
    public static ItemStack carrier(ResourceLocation fluidId) {
        return ItemStack.EMPTY;
    }
}
