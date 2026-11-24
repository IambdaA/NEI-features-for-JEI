package neiaddon;
import net.minecraft.world.item.ItemStack;
/** Stub for the plain-Java checks: EMI is not loaded, so the addon's own stores decide everything. */
public final class EmiCompat {
    public static boolean present() {
        return false;
    }
    public static boolean defaultsReady() {
        return false;
    }
    public static boolean loading() {
        return false;
    }
    public static String defaultRecipe(ItemStack item) {
        return null;
    }
    public static String defaultItemOf(String recipeId) {
        return null;
    }
    public static boolean setDefault(ItemStack item, String recipeId, ItemStack hint) {
        return false;
    }
    public static void clearDefault(ItemStack item, String onlyRecipeId) {
    }
    public static void clearOtherDefaults(String recipeId, String keepItemId) {
    }
}
