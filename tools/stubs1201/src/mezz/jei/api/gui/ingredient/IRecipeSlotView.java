package mezz.jei.api.gui.ingredient;
import java.util.List;
import java.util.stream.Stream;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.world.item.ItemStack;
/** Stub for the plain-Java checks (1.20.1): the slot calls RecipeShape makes. */
public interface IRecipeSlotView {
    RecipeIngredientRole getRole();
    Stream<ItemStack> getItemStacks();
    <T> Stream<T> getIngredients(IIngredientType<T> type);
    List<ITypedIngredient<?>> getAllIngredientsList();
    Stream<ITypedIngredient<?>> getAllIngredients();
}
