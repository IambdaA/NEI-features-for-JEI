package neiaddon;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.item.ItemStack;
/**
 * Stub for the plain-Java checks, standing in for the real store (whose Target/EMI code needs the game jars):
 * shapes registered by the check are found back by id, and onlyRecipeFor answers like the real store does for
 * a vanilla-sized world: the one registered recipe that produces the item, or null when several do.
 */
public final class RecipeLookup {
    private static final Map<String, RecipeShape> byId = new LinkedHashMap<>();
    public static void clear() {
        byId.clear();
    }
    public static void register(RecipeShape shape) {
        byId.put(shape.id, shape);
    }
    public static RecipeShape find(String recipeId, ItemStack hint) {
        RecipeShape shape = byId.get(recipeId);
        return shape == null ? null : shape.copy();
    }
    public static String onlyRecipeFor(ItemStack stack) {
        if (stack.isEmpty()) return null;
        String id = RecipeData.idOf(stack);
        String found = null;
        for (RecipeShape s : byId.values()) {
            boolean produces = false;
            outer:
            for (RecipeShape.Slot o : s.outputs) {
                for (RecipeShape.Alt a : o.alts) {
                    if (id.equals(a.id())) {
                        produces = true;
                        break outer;
                    }
                }
            }
            if (!produces) continue;
            if (found != null) return null;
            found = s.id;
        }
        return found;
    }
}
