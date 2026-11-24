package mezz.jei.api.ingredients;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;
/** Stub for the plain-Java checks (1.20.1). */
public interface ITypedIngredient<T> {
    IIngredientType<T> getType();
    T getIngredient();
    Optional<ItemStack> getItemStack();
}
