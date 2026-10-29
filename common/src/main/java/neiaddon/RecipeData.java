package neiaddon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * One recipe turned into what the bookmark panel stores (no JEI classes, so the model code stays testable).
 *
 * <p>
 * Results: one {@link Part} per distinct item, counts added up. Ingredients: one {@link Part} per distinct
 * <em>chosen</em> item, its factor being the sum over every slot that accepts it (six slots with one iron ingot each =
 * 6), with {@link Part#options} listing every item such a slot accepts (tags).
 */
public final class RecipeData {

    public static final class Part {

        /** The item (count 1). */
        public final ItemStack stack;
        /** How many one run of the recipe makes (results) or uses (ingredients). */
        public final long factor;
        /** Registry ids of every item the slot accepts; contains the id of {@link #stack}. */
        public final List<String> options;

        public Part(ItemStack stack, long factor, List<String> options) {
            this.stack = stack.copyWithCount(1);
            this.factor = Math.max(1, factor);
            this.options = options;
        }

        public String itemId() {
            return idOf(stack);
        }
    }

    public final String recipeId;
    public final List<Part> results = new ArrayList<>();
    public final List<Part> ingredients = new ArrayList<>();

    public RecipeData(String recipeId) {
        this.recipeId = recipeId;
    }

    public static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
            .toString();
    }

    public boolean isEmpty() {
        return results.isEmpty() || ingredients.isEmpty();
    }
}
