package mezz.jei.api.runtime;
import mezz.jei.api.helpers.IJeiHelpers;
import mezz.jei.api.ingredients.IIngredientManager;
/** Stub for the plain-Java checks (1.20.1): only the two accessors the code reads are here. */
public interface IJeiRuntime {
    IJeiHelpers getJeiHelpers();
    IIngredientManager getIngredientManager();
}
