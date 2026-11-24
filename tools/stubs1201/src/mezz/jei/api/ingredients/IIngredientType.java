package mezz.jei.api.ingredients;
import net.minecraft.world.level.material.Fluid;
/** Stub for the plain-Java checks (1.20.1): the calls RecipeShape makes on a type. */
public interface IIngredientType<T> {
    Class<? extends T> getIngredientClass();
    Fluid getBase(T ingredient);
}
