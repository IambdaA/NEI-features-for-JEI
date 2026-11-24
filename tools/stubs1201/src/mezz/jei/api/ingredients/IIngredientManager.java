package mezz.jei.api.ingredients;
/** Stub for the plain-Java checks (1.20.1). */
public interface IIngredientManager {
    <T> IIngredientHelper<T> getIngredientHelper(IIngredientType<T> type);
}
