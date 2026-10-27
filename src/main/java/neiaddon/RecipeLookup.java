package neiaddon;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusFactory;
import mezz.jei.api.recipe.IRecipeLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Finds a recipe again from the id saved in the favorites file (see {@link RecipeShape#idOf}). The result is cached
 * until JEI reloads. Recipes whose id is not found (mod removed, recipe changed) simply resolve to null.
 */
public final class RecipeLookup {

    private static final Map<String, RecipeShape> cache = new HashMap<>();
    private static final java.util.Set<String> missing = new java.util.HashSet<>();
    private static final Map<String, Target<?>> targets = new HashMap<>();

    private RecipeLookup() {}

    public static void reset() {
        cache.clear();
        missing.clear();
        targets.clear();
    }

    /**
     * A recipe as JEI's own "+" button sees it: what its transfer handler needs to fill a crafting grid with the
     * recipe's ingredients.
     */
    public static final class Target<T> {

        private final IRecipeCategory<T> category;
        private final T recipe;
        private final IRecipeSlotsView slots;

        Target(IRecipeCategory<T> category, T recipe, IRecipeSlotsView slots) {
            this.category = category;
            this.recipe = recipe;
            this.slots = slots;
        }

        /** The recipe type's icon (shown on bookmarks), or null. */
        public mezz.jei.api.gui.drawable.IDrawable icon() {
            return category.getIcon();
        }

        /** A drawable copy of the recipe (for tooltips), or null. */
        public IRecipeLayoutDrawable<T> layout() {
            IJeiRuntime rt = Calculator.runtime;
            if (rt == null) return null;
            return rt.getRecipeManager()
                .createRecipeLayoutDrawable(
                    category,
                    recipe,
                    rt.getJeiHelpers()
                        .getFocusFactory()
                        .getEmptyFocusGroup())
                .orElse(null);
        }

        /** Null when the open container has no transfer handler for this recipe. */
        private IRecipeTransferHandler<AbstractContainerMenu, T> handler(IJeiRuntime rt, AbstractContainerMenu menu) {
            return rt.getRecipeTransferManager()
                .getRecipeTransferHandler(menu, category)
                .orElse(null);
        }

        @SuppressWarnings("removal")
        /** Can the open container take this recipe right now (a handler exists and every ingredient is in the inventory)? */
        public boolean canTransfer(AbstractContainerMenu menu, Player player) {
            IJeiRuntime rt = Calculator.runtime;
            if (rt == null) return false;
            IRecipeTransferHandler<AbstractContainerMenu, T> handler = handler(rt, menu);
            return handler != null && handler.transferRecipe(menu, recipe, slots, player, false, false) == null;
        }

        /** Moves the ingredients for one craft into the grid (JEI sends the request to the server). */
        @SuppressWarnings("removal")
        public boolean transfer(AbstractContainerMenu menu, Player player) {
            IJeiRuntime rt = Calculator.runtime;
            if (rt == null) return false;
            IRecipeTransferHandler<AbstractContainerMenu, T> handler = handler(rt, menu);
            return handler != null && handler.transferRecipe(menu, recipe, slots, player, false, true) == null;
        }
    }

    /** The recipe's transfer target, or null if the recipe cannot be found. */
    public static Target<?> findTarget(String recipeId, ItemStack hint) {
        if (!targets.containsKey(recipeId)) {
            // a recipe registered from a heart has a cached shape but no transfer target: search for it for real
            RecipeShape cached = cache.remove(recipeId);
            find(recipeId, hint);
            if (cached != null && !cache.containsKey(recipeId)) {
                cache.put(recipeId, cached);
                missing.remove(recipeId);
            }
        }
        return targets.get(recipeId);
    }

    /** Makes a recipe findable by its id without asking JEI (used by tests, and for recipes read from a layout). */
    public static void register(RecipeShape shape) {
        cache.put(shape.id, shape);
        missing.remove(shape.id);
    }

    /**
     * @param recipeId the saved recipe id
     * @param hint     an item the recipe makes (the favorite's item); only recipes making it are searched, which
     *                 keeps this fast
     * @return a fresh copy of the recipe's shape, or null
     */
    public static RecipeShape find(String recipeId, ItemStack hint) {
        RecipeShape cached = cache.get(recipeId);
        if (cached != null) return cached.copy();
        if (missing.contains(recipeId)) return null;
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null) return null;
        RecipeShape found = null;
        try {
            int bar = recipeId.indexOf('|');
            int hash = recipeId.indexOf('#');
            boolean byIdentifier = bar >= 0 && (hash < 0 || bar < hash);
            int sep = byIdentifier ? bar : hash;
            if (sep > 0) {
                String typeUid = recipeId.substring(0, sep);
                String rest = recipeId.substring(sep + 1);
                Optional<RecipeType<?>> type = rt.getRecipeManager()
                    .getRecipeType(new ResourceLocation(typeUid));
                if (type.isPresent()) {
                    found = search(rt, type.get(), recipeId, byIdentifier, rest, hint, true);
                    if (found == null && hint != null && !hint.isEmpty()) {
                        found = search(rt, type.get(), recipeId, byIdentifier, rest, hint, false);
                    }
                }
            }
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not look up recipe {}", recipeId, e);
        }
        if (found == null) {
            missing.add(recipeId);
            return null;
        }
        cache.put(recipeId, found);
        return found.copy();
    }

    private static <T> RecipeShape search(IJeiRuntime rt, RecipeType<T> type, String recipeId, boolean byIdentifier,
        String identifier, ItemStack hint, boolean focused) {
        IRecipeManager manager = rt.getRecipeManager();
        IFocusFactory focuses = rt.getJeiHelpers()
            .getFocusFactory();
        IRecipeCategory<T> category = manager.getRecipeCategory(type);
        IRecipeLookup<T> lookup = manager.createRecipeLookup(type)
            .includeHidden();
        if (focused && hint != null && !hint.isEmpty()) {
            IFocus<ItemStack> focus = focuses.createFocus(RecipeIngredientRole.OUTPUT, VanillaTypes.ITEM_STACK, hint);
            lookup = lookup.limitFocus(List.of(focus));
        } else if (focused) {
            return null;
        }
        for (T recipe : lookup.get()
            .toList()) {
            if (byIdentifier) {
                ResourceLocation id = category.getRegistryName(recipe);
                if (id == null || !identifier.equals(id.toString())) continue;
            }
            Optional<IRecipeLayoutDrawable<T>> layout = manager
                .createRecipeLayoutDrawable(category, recipe, focuses.getEmptyFocusGroup());
            if (layout.isEmpty()) continue;
            if (!byIdentifier && !recipeId.equals(RecipeShape.idOf(layout.get()))) continue;
            targets.put(recipeId, new Target<>(category, recipe, layout.get().getRecipeSlotsView()));
            return RecipeShape.read(layout.get()
                .getRecipeSlotsView(), recipeId);
        }
        return null;
    }
}
