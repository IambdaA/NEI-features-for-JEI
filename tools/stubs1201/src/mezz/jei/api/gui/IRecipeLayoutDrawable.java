package mezz.jei.api.gui;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.category.IRecipeCategory;
/** Stub for the plain-Java checks (1.20.1). */
public interface IRecipeLayoutDrawable<R> {
    IRecipeSlotsView getRecipeSlotsView();
    IRecipeCategory<R> getRecipeCategory();
    R getRecipe();
}
