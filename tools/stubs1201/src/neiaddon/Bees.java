package neiaddon;

import java.util.List;

import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.network.chat.Component;

/**
 * Stub for the plain-Java checks: Productive Bees is not there, so no id is a bee's and no bee has a tooltip. Only what
 * Bookmarks reads is present; the drawing and the JEI lookups belong to the game side, which these checks never compile.
 */
public final class Bees {

    public static final String PREFIX = "bee:";

    public static boolean isBeeId(String id) {
        return false;
    }

    public static List<Component> tooltip(String id) {
        return List.of();
    }

    public static void showRecipes(String id, RecipeIngredientRole role) {
    }
}
