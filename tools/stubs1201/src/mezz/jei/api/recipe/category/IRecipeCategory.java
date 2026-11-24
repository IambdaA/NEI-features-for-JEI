package mezz.jei.api.recipe.category;
import mezz.jei.api.recipe.RecipeType;
import net.minecraft.resources.ResourceLocation;
/** Stub for the plain-Java checks (1.20.1): the two calls RecipeShape.idOf makes. */
public interface IRecipeCategory<R> {
    RecipeType<R> getRecipeType();
    ResourceLocation getRegistryName(R recipe);
}
