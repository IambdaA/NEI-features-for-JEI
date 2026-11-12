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
 * until JEI reloads. Recipes whose id is not found (mod removed, recipe changed) simply resolve to null. Under EMI,
 * recipes JEI does not have (EMI blocks JEI's plugins) are looked up in EMI.
 */
public final class RecipeLookup {

    private static final Map<String, RecipeShape> cache = new HashMap<>();
    private static final java.util.Set<String> missing = new java.util.HashSet<>();
    private static final Map<String, Target<?>> targets = new HashMap<>();
    /** Item id -> the id of the only recipe making it, or "" when it has none or several. */
    private static final Map<String, String> onlyRecipes = new HashMap<>();
    /** Counts the JEI reloads, so pictures of recipes kept elsewhere know when to be dropped. */
    private static int generation;
    /** The EMI reload count the caches were last checked against (see {@link #checkEmi}). */
    private static int emiGeneration;

    private RecipeLookup() {}

    public static int generation() {
        checkEmi();
        return generation;
    }

    /** Drops the caches when EMI has reloaded since they were filled: its recipes may have changed. */
    private static void checkEmi() {
        if (EmiCompat.recipesAvailable()) EmiCompat.loading();
        if (EmiCompat.generation() != emiGeneration) {
            emiGeneration = EmiCompat.generation();
            reset();
        }
    }

    public static void reset() {
        generation++;
        onlyRecipes.clear();
        cache.clear();
        missing.clear();
        targets.clear();
    }

    /**
     * A recipe as JEI's own "+" button sees it: what its transfer handler needs to fill a crafting grid with the
     * recipe's ingredients. A recipe found in EMI has no JEI slots, and is shown, filled and explained by EMI (see
     * {@link #ofEmi}); then the JEI parts may be null too.
     */
    public static final class Target<T> {

        private final IRecipeCategory<T> category;
        private final T recipe;
        private final IRecipeSlotsView slots;
        /** The EMI side of a recipe found in EMI, or null for a JEI recipe. */
        private final EmiCompat.RecipeHandle emi;

        Target(IRecipeCategory<T> category, T recipe, IRecipeSlotsView slots) {
            this(category, recipe, slots, null);
        }

        private Target(IRecipeCategory<T> category, T recipe, IRecipeSlotsView slots, EmiCompat.RecipeHandle emi) {
            this.category = category;
            this.recipe = recipe;
            this.slots = slots;
            this.emi = emi;
        }

        /**
         * Opens JEI's recipe screen for the focus as usual (all its recipes), then turns to this recipe's category and to
         * the page that starts with it. JEI has no API for that, so its recipe screen is driven by reflection; if that
         * fails the screen simply stays on its first page. A recipe found in EMI is opened in EMI.
         */
        public void show(java.util.List<mezz.jei.api.recipe.IFocus<?>> focuses) {
            if (emi != null) {
                emi.show();
                return;
            }
            var gui = Calculator.runtime.getRecipesGui();
            gui.show(focuses);
            try {
                Object logic = JeiAreas.field(gui.getClass(), "logic")
                    .get(gui);
                java.lang.reflect.Method setCategory = logic.getClass()
                    .getMethod("setRecipeCategory", IRecipeCategory.class);
                setCategory.setAccessible(true);
                setCategory.invoke(logic, category);
                Object state = JeiAreas.field(logic.getClass(), "state")
                    .get(logic);
                java.util.List<?> recipes = (java.util.List<?>) JeiAreas.invoke(JeiAreas.invoke(state, "getFocusedRecipes"), "getRecipes");
                int index = recipes.indexOf(recipe);
                if (index <= 0) return;
                java.lang.reflect.Method setIndex = null;
                for (Class<?> c = state.getClass(); c != null && setIndex == null; c = c.getSuperclass()) {
                    try {
                        setIndex = c.getDeclaredMethod("setRecipeIndex", int.class);
                    } catch (NoSuchMethodException ignored) {}
                }
                if (setIndex != null) {
                    setIndex.setAccessible(true);
                    setIndex.invoke(state, index);
                } else {
                    // older JEI (15.20, 19.25) has no setter, only the field
                    JeiAreas.field(state.getClass(), "recipeIndex")
                        .setInt(state, index);
                }
                Object listener = JeiAreas.field(logic.getClass(), "stateListener")
                    .get(logic);
                JeiAreas.invoke(listener, "onStateChange");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                NeiAddon.LOGGER.debug("Could not turn JEI's recipe screen to the bookmarked recipe", e);
            }
        }

        /**
         * The recipe type's icon (shown on bookmarks), or null: the category's own icon, else a picture of its first catalyst,
         * as JEI's own bookmark list does (JEI's crafting category has no icon of its own, so it shows the crafting table).
         */
        public mezz.jei.api.gui.drawable.IDrawable icon() {
            if (emi != null) {
                mezz.jei.api.gui.drawable.IDrawable icon = emi.icon();
                if (icon != null || category == null) return icon;
            }
            mezz.jei.api.gui.drawable.IDrawable own = category.getIcon();
            IJeiRuntime rt = Calculator.runtime;
            if (own != null || rt == null) return own;
            return rt.getRecipeManager()
                .createRecipeCatalystLookup(category.getRecipeType())
                .get()
                .findFirst()
                .map(catalyst -> rt.getJeiHelpers()
                    .getGuiHelper()
                    .createDrawableIngredient(catalyst))
                .orElse(null);
        }

        /** A drawable copy of the recipe (for tooltips), or null. */
        public IRecipeLayoutDrawable<T> layout() {
            IJeiRuntime rt = Calculator.runtime;
            if (rt == null || category == null || recipe == null) return null;
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
            if (emi != null) return emi.canFill(menu);
            IJeiRuntime rt = Calculator.runtime;
            if (rt == null) return false;
            IRecipeTransferHandler<AbstractContainerMenu, T> handler = handler(rt, menu);
            return handler != null && handler.transferRecipe(menu, recipe, slots, player, false, false) == null;
        }

        /** Moves the ingredients for one craft into the grid (JEI sends the request to the server). */
        public boolean transfer(AbstractContainerMenu menu, Player player) {
            return transfer(menu, player, false);
        }

        /**
         * Why the open container cannot take this recipe now, for the player: the ingredients JEI found missing (from the
         * slots its transfer error names), else the error's kind; null when it can.
         */
        @SuppressWarnings("removal")
        public String whyNot(AbstractContainerMenu menu, Player player) {
            if (emi != null) return emi.whyNot(menu);
            IJeiRuntime rt = Calculator.runtime;
            if (rt == null) return null;
            IRecipeTransferHandler<AbstractContainerMenu, T> handler = handler(rt, menu);
            if (handler == null) return "it is not made in this crafting grid";
            Object error = handler.transferRecipe(menu, recipe, slots, player, false, false);
            if (error == null) return null;
            Map<String, Integer> missing = new java.util.LinkedHashMap<>();
            try {
                for (Class<?> c = error.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (!java.util.Collection.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        Object value = f.get(error);
                        if (!(value instanceof java.util.Collection<?> list)) continue;
                        for (Object o : list) {
                            if (o instanceof mezz.jei.api.gui.ingredient.IRecipeSlotView view) {
                                missing.merge(view.getDisplayedIngredient()
                                    .map(ti -> nameOf(rt, ti))
                                    .orElse("?"), 1, Integer::sum);
                            }
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                missing.clear();
            }
            if (missing.isEmpty()) {
                try {
                    return "JEI says " + JeiAreas.invoke(error, "getType");
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return "JEI cannot move it into the grid";
                }
            }
            StringBuilder out = new StringBuilder("missing ");
            for (Map.Entry<String, Integer> e : missing.entrySet()) {
                if (out.length() > 8) out.append(", ");
                out.append(e.getKey());
                if (e.getValue() > 1) out.append(" x")
                    .append(e.getValue());
            }
            return out.toString();
        }

        private static <I> String nameOf(IJeiRuntime rt, mezz.jei.api.ingredients.ITypedIngredient<I> ingredient) {
            return rt.getIngredientManager()
                .getIngredientHelper(ingredient.getType())
                .getDisplayName(ingredient.getIngredient());
        }

        /** The same, and with {@code max} as many crafts as the grid takes (what Shift does on JEI's "+" button). */
        @SuppressWarnings("removal")
        public boolean transfer(AbstractContainerMenu menu, Player player, boolean max) {
            if (emi != null) return emi.fill(menu, max);
            IJeiRuntime rt = Calculator.runtime;
            if (rt == null) return false;
            IRecipeTransferHandler<AbstractContainerMenu, T> handler = handler(rt, menu);
            return handler != null && handler.transferRecipe(menu, recipe, slots, player, max, true) == null;
        }

        /**
         * The transfer target of a recipe found in EMI: the JEI category and recipe it maps to when there are both (so the
         * JEI picture and icon work), else a target with only EMI's side.
         */
        static Target<?> ofEmi(EmiCompat.RecipeHandle handle) {
            IRecipeCategory<?> category = handle.jeiCategory();
            Object recipe = handle.jeiRecipe();
            if (category != null && recipe != null && category.getRecipeType()
                .getRecipeClass()
                .isInstance(recipe)) {
                return typed(category, recipe, handle);
            }
            return new Target<Object>(null, null, null, handle);
        }

        private static <T> Target<T> typed(IRecipeCategory<T> category, Object recipe, EmiCompat.RecipeHandle handle) {
            return new Target<>(category, category.getRecipeType()
                .getRecipeClass()
                .cast(recipe), null, handle);
        }
    }

    /** Moves the ingredients of the recipe a layout shows into the open container, like JEI's "+" button. */
    public static <T> boolean transfer(IRecipeLayoutDrawable<T> layout, AbstractContainerMenu menu, Player player, boolean max) {
        return new Target<>(layout.getRecipeCategory(), layout.getRecipe(), layout.getRecipeSlotsView()).transfer(menu, player, max);
    }

    /** What a search for the recipes making one item found. */
    private static final class Count {

        int recipes;
        RecipeShape shape;
    }

    /**
     * NEI's generated single-recipe favorite: the id of the recipe when exactly one recipe in all of JEI makes the item
     * (and it has ingredients), else null. The answer is kept until JEI reloads.
     */
    public static String onlyRecipeFor(ItemStack stack) {
        checkEmi();
        if (stack.isEmpty()) return null;
        String key = RecipeData.idOf(stack);
        String known = onlyRecipes.get(key);
        if (known != null) return known.isEmpty() ? null : known;
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null) return null;
        if (EmiCompat.recipesAvailable()) {
            if (EmiCompat.loading()) return null;
            List<RecipeShape> emi = EmiCompat.recipesFor(stack);
            // EMI may have started reloading since the check above: cache nothing then
            if (emi == null && EmiCompat.loading()) return null;
            if (emi != null) {
                String emiFound = "";
                if (emi.size() == 1 && !emi.get(0).inputs.isEmpty()) {
                    RecipeShape shape = emi.get(0);
                    emiFound = shape.id;
                    cache.put(emiFound, shape);
                    missing.remove(emiFound);
                }
                onlyRecipes.put(key, emiFound);
                return emiFound.isEmpty() ? null : emiFound;
            }
        }
        String found = "";
        try {
            IFocus<?> focus = FluidStacks.focus(rt, RecipeIngredientRole.OUTPUT, stack);
            IRecipeManager manager = rt.getRecipeManager();
            Count count = new Count();
            for (IRecipeCategory<?> category : manager.createRecipeCategoryLookup()
                .limitFocus(List.of(focus))
                .get()
                .toList()) {
                scan(rt, manager, category, focus, count);
                if (count.recipes > 1) break;
            }
            if (count.recipes == 1 && count.shape != null && !count.shape.inputs.isEmpty()) found = count.shape.id;
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not look for the recipes of {}", key, e);
        }
        onlyRecipes.put(key, found);
        return found.isEmpty() ? null : found;
    }

    private static <T> void scan(IJeiRuntime rt, IRecipeManager manager, IRecipeCategory<T> category, IFocus<?> focus, Count count) {
        for (T recipe : manager.createRecipeLookup(category.getRecipeType())
            .limitFocus(List.of(focus))
            .get()
            .toList()) {
            Optional<IRecipeLayoutDrawable<T>> layout = manager.createRecipeLayoutDrawable(
                category,
                recipe,
                rt.getJeiHelpers()
                    .getFocusFactory()
                    .getEmptyFocusGroup());
            if (layout.isEmpty()) continue;
            count.recipes++;
            if (count.recipes > 1) return;
            String id = RecipeShape.idOf(layout.get());
            RecipeShape shape = RecipeShape.read(layout.get()
                .getRecipeSlotsView(), id);
            targets.put(id, new Target<>(category, recipe, layout.get()
                .getRecipeSlotsView()));
            cache.put(id, shape);
            missing.remove(id);
            count.shape = shape;
        }
    }

    /** The recipe's transfer target, or null if the recipe cannot be found. */
    public static Target<?> findTarget(String recipeId, ItemStack hint) {
        checkEmi();
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
        checkEmi();
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
        checkEmi();
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
        if (found == null && EmiCompat.recipesAvailable()) {
            EmiCompat.Found emi = EmiCompat.find(recipeId, hint);
            if (emi != null) {
                found = emi.shape();
                targets.put(recipeId, Target.ofEmi(emi.handle()));
            }
        }
        if (found == null) {
            // while EMI is still loading its recipes, the miss is not remembered
            if (!EmiCompat.loading()) missing.add(recipeId);
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
            IFocus<?> focus = FluidStacks.focus(rt, RecipeIngredientRole.OUTPUT, hint);
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
