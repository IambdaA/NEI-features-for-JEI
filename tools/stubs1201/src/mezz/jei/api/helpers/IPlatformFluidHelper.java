package mezz.jei.api.helpers;
import mezz.jei.api.ingredients.IIngredientType;
/** Stub for the plain-Java checks (1.20.1): the shape of the calls RecipeShape makes. */
public interface IPlatformFluidHelper<T> {
    int bucketVolume();
    IIngredientType<T> getFluidIngredientType();
}
