package neiaddon.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import neiaddon.Calculator;
import neiaddon.EmiCompat;
import neiaddon.FluidStacks;
import neiaddon.NeiAddon;
import neiaddon.RecipeShape;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * EMI's recipes for the addon (see {@link EmiCompat}). EMI blocks JEI's own plugins, so JEI knows no vanilla recipes and
 * none of the mods that have an EMI plugin; the addon asks here instead. A recipe comes from, in this order: the JEI
 * recipe EMI shows through JEMI, the JEI recipe behind an EMI recipe that has a backing recipe, or else EMI's own data.
 * All state is static: the EMI plugin makes a new bridge on every EMI reload.
 */
public final class EmiBridge implements EmiCompat.Hooks {

    /** The step that made the last shape: "jemi", "backing" or "own". Only the test driver reads it (by reflection). */
    static volatile String lastStep;

    private static final String JEMI_RECIPE = "dev.emi.emi.jemi.JemiRecipe";
    /** Categories that are not recipes: tags, ingredient info and the like. */
    private static final Set<String> SKIPPED = Set.of("emi:tag", "emi:ingredient", "emi:resolution", "emi:info");
    /** EMI names these recipe types differently from JEI 15. */
    private static final Map<String, String> ALIASES = Map.of(
        "minecraft:smelting", "minecraft:furnace",
        "minecraft:campfire_cooking", "minecraft:campfire");

    private static Method isLoaded;
    private static boolean loadedFailed;
    private static Field jemiCategory;
    private static Field jemiRecipe;
    private static boolean warnedRecipe;
    private static boolean iconBroken;

    /** A recipe as the bridge maps it: its shape, and the JEI category and recipe behind it (null for EMI's own data). */
    private record Mapped(RecipeShape shape, IRecipeCategory<?> jeiCategory, Object jeiRecipe) {}

    @Override
    public boolean cheatMode() {
        return EmiApi.isCheatMode();
    }

    /** EMI's reload manager is not in its API, so it is asked by reflection; if that fails, EMI counts as loaded. */
    @Override
    public boolean loaded() {
        if (loadedFailed) return true;
        try {
            if (isLoaded == null) {
                isLoaded = Class.forName("dev.emi.emi.runtime.EmiReloadManager").getMethod("isLoaded");
            }
            return (Boolean) isLoaded.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            loadedFailed = true;
            NeiAddon.LOGGER.warn("Could not ask EMI whether its recipes are loaded; the addon takes them as loaded", e);
            return true;
        }
    }

    @Override
    public List<RecipeShape> recipesFor(ItemStack stack) {
        Map<String, RecipeShape> byId = new LinkedHashMap<>();
        if (stack.isEmpty()) return new ArrayList<>();
        for (EmiRecipe r : EmiApi.getRecipeManager()
            .getRecipesByOutput(emiStackOf(stack))) {
            try {
                if (!usable(r)) continue;
                Mapped m = map(r);
                if (m != null) byId.putIfAbsent(m.shape().id, m.shape());
            } catch (RuntimeException | LinkageError e) {
                recipeFailed(e);
            }
        }
        return new ArrayList<>(byId.values());
    }

    @Override
    public EmiCompat.Found find(String recipeId, ItemStack hint) {
        int bar = recipeId.indexOf('|');
        int hash = recipeId.indexOf('#');
        boolean byIdentifier = bar >= 0 && (hash < 0 || bar < hash);
        int sep = byIdentifier ? bar : hash;
        if (sep <= 0) return null;
        String rest = recipeId.substring(sep + 1);
        if (byIdentifier) {
            EmiCompat.Found found = byRecipeId(rest, recipeId);
            if (found != null) return found;
        }
        if (hint == null || hint.isEmpty()) return null;
        for (EmiRecipe r : EmiApi.getRecipeManager()
            .getRecipesByOutput(emiStackOf(hint))) {
            try {
                if (!usable(r)) continue;
                Mapped m = map(r);
                if (m == null) continue;
                boolean accepted = byIdentifier ? rest.equals(restOf(m.shape().id)) : recipeId.equals(m.shape().id);
                if (accepted) return foundOf(r, m, recipeId);
            } catch (RuntimeException | LinkageError e) {
                recipeFailed(e);
            }
        }
        return null;
    }

    /** The EMI recipe with this id (a JEI-era category may name it differently), or null when EMI has none. */
    private static EmiCompat.Found byRecipeId(String identifier, String recipeId) {
        ResourceLocation id = ResourceLocation.tryParse(identifier);
        if (id == null) return null;
        try {
            EmiRecipe r = EmiApi.getRecipeManager()
                .getRecipe(id);
            if (r == null || !usable(r)) return null;
            Mapped m = map(r);
            if (m == null || !identifier.equals(restOf(m.shape().id))) return null;
            return foundOf(r, m, recipeId);
        } catch (RuntimeException | LinkageError e) {
            recipeFailed(e);
            return null;
        }
    }

    /** The addon's view of an EMI recipe: its shape under the addon id, and a target that shows and fills it. */
    private static EmiCompat.Found foundOf(EmiRecipe r, Mapped m, String recipeId) {
        return new EmiCompat.Found(m.shape().withId(recipeId), new EmiTarget(r, m.jeiCategory(), m.jeiRecipe()));
    }

    @Override
    public String categoryName(String id) {
        EmiRecipeCategory category = categoryOf(id);
        return category == null ? null : category.getName().getString();
    }

    @Override
    public IDrawable categoryIcon(String id) {
        EmiRecipeCategory category = categoryOf(id);
        return category == null ? null : icon(category);
    }

    /** The EMI category with this id, or null. */
    private static EmiRecipeCategory categoryOf(String id) {
        for (EmiRecipeCategory category : EmiApi.getRecipeManager()
            .getCategories()) {
            if (category.getId()
                .toString()
                .equals(id)) return category;
        }
        return null;
    }

    /**
     * EMI's category icon as a JEI drawable, 16 by 16, drawn by the category's own render. If that fails, one warning is
     * logged and nothing is drawn from then on.
     */
    static IDrawable icon(EmiRecipeCategory category) {
        return new IDrawable() {

            @Override
            public int getWidth() {
                return 16;
            }

            @Override
            public int getHeight() {
                return 16;
            }

            @Override
            public void draw(GuiGraphics g, int xOffset, int yOffset) {
                if (iconBroken) return;
                try {
                    category.render(g, xOffset, yOffset, 0f);
                } catch (RuntimeException | LinkageError e) {
                    iconBroken = true;
                    NeiAddon.LOGGER.warn("Could not draw an EMI category icon; the addon draws none from now on", e);
                }
            }
        };
    }

    /** The part of a {@code type|identifier} id after the first bar, or null for any other id. */
    private static String restOf(String id) {
        int bar = id.indexOf('|');
        return bar < 0 ? null : id.substring(bar + 1);
    }

    /** Whether the recipe is a real recipe with ingredients (not a tag, info or resolution page). */
    private static boolean usable(EmiRecipe r) {
        return !SKIPPED.contains(r.getCategory()
            .getId()
            .toString()) && !r.getInputs()
                .isEmpty();
    }

    /** The recipe as a shape, or null. Each step falls through to the next when it fails or has no inputs. */
    static Mapped map(EmiRecipe r) {
        Mapped m = fromJemi(r);
        if (m != null && !m.shape().inputs.isEmpty()) {
            lastStep = "jemi";
            return m;
        }
        m = fromBacking(r);
        if (m != null && !m.shape().inputs.isEmpty()) {
            lastStep = "backing";
            return m;
        }
        RecipeShape shape;
        try {
            shape = own(r);
        } catch (RuntimeException | LinkageError e) {
            recipeFailed(e);
            return null;
        }
        if (shape == null) return null;
        lastStep = "own";
        return new Mapped(shape, null, null);
    }

    /** The JEI recipe EMI shows for a JEI recipe (JEMI's recipe objects keep the JEI category and recipe as fields). */
    private static Mapped fromJemi(EmiRecipe r) {
        if (!r.getClass()
            .getName()
            .equals(JEMI_RECIPE)) return null;
        try {
            if (jemiCategory == null) {
                jemiCategory = r.getClass()
                    .getField("category");
                jemiRecipe = r.getClass()
                    .getField("recipe");
            }
            Object category = jemiCategory.get(r);
            Object recipe = jemiRecipe.get(r);
            if (!(category instanceof IRecipeCategory<?> c) || recipe == null) return null;
            RecipeShape shape = jeiShape(c, recipe);
            return shape == null ? null : new Mapped(shape, c, recipe);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.debug("Could not read the JEI recipe of an EMI recipe; EMI's own data is used", e);
            return null;
        }
    }

    /** The JEI recipe behind an EMI recipe that has a backing recipe, looked up in JEI under EMI's category id. */
    private static Mapped fromBacking(EmiRecipe r) {
        try {
            Object backing = r.getBackingRecipe();
            if (backing == null || !Calculator.available()) return null;
            IRecipeManager manager = Calculator.runtime.getRecipeManager();
            ResourceLocation category = r.getCategory()
                .getId();
            Optional<RecipeType<?>> type = manager.getRecipeType(category);
            String alias = ALIASES.get(category.toString());
            if (type.isEmpty() && alias != null) type = manager.getRecipeType(new ResourceLocation(alias));
            if (type.isEmpty()) return null;
            return backingShape(manager, type.get(), backing);
        } catch (RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.debug("Could not read the backing recipe of an EMI recipe; EMI's own data is used", e);
            return null;
        }
    }

    private static <T> Mapped backingShape(IRecipeManager manager, RecipeType<T> type, Object backing) {
        if (!type.getRecipeClass()
            .isInstance(backing)) return null;
        IRecipeCategory<T> category = manager.getRecipeCategory(type);
        RecipeShape shape = jeiShape(category, backing);
        return shape == null ? null : new Mapped(shape, category, backing);
    }

    /** The JEI layout of a recipe, read as a shape; it has the same id JEI gives the recipe. */
    private static <T> RecipeShape jeiShape(IRecipeCategory<T> category, Object recipe) {
        if (!Calculator.available()) return null;
        IJeiRuntime rt = Calculator.runtime;
        T cast = category.getRecipeType()
            .getRecipeClass()
            .cast(recipe);
        Optional<IRecipeLayoutDrawable<T>> layout = rt.getRecipeManager()
            .createRecipeLayoutDrawable(category, cast, rt.getJeiHelpers()
                .getFocusFactory()
                .getEmptyFocusGroup());
        return layout.isEmpty() ? null : RecipeShape.read(layout.get());
    }

    /** The shape of an EMI recipe, as {@link #find} and {@link #recipesFor} give it, or null when it has none. */
    static RecipeShape shapeFor(EmiRecipe r) {
        try {
            if (!usable(r)) return null;
            Mapped m = map(r);
            return m == null ? null : m.shape();
        } catch (RuntimeException | LinkageError e) {
            recipeFailed(e);
            return null;
        }
    }

    /** Every item and fluid an EMI ingredient accepts, each once (by id), as alternatives; empty for an empty ingredient. */
    static List<RecipeShape.Alt> altsOf(EmiIngredient ing) {
        List<RecipeShape.Alt> alts = new ArrayList<>();
        if (ing.isEmpty()) return alts;
        Set<String> seen = new HashSet<>();
        for (EmiStack s : ing.getEmiStacks()) {
            long amount = s.getAmount() > 0 ? s.getAmount() : ing.getAmount();
            RecipeShape.Alt alt = altFor(s, amount);
            if (alt != null && seen.add(alt.id())) alts.add(alt);
        }
        return alts;
    }

    /** EMI's own data: each input ingredient is a slot, each output a slot; catalysts are not inputs. */
    private static RecipeShape own(EmiRecipe r) {
        List<List<RecipeShape.Alt>> inputs = new ArrayList<>();
        for (EmiIngredient ing : r.getInputs()) {
            List<RecipeShape.Alt> alts = altsOf(ing);
            if (!alts.isEmpty()) inputs.add(alts);
        }
        if (inputs.isEmpty()) return null;
        List<List<RecipeShape.Alt>> outputs = new ArrayList<>();
        for (EmiStack s : r.getOutputs()) {
            RecipeShape.Alt alt = altFor(s, s.getAmount());
            if (alt != null) outputs.add(List.of(alt));
        }
        String identifier = r.getId() == null ? null : r.getId()
            .toString();
        String id = RecipeShape.idFor(r.getCategory()
            .getId()
            .toString(), identifier, inputs, outputs);
        return RecipeShape.of(id, inputs, outputs);
    }

    /** One EMI stack as an alternative; null for an empty item. Fluids are counted in millibuckets. */
    static RecipeShape.Alt altFor(EmiStack s, long amount) {
        if (s.getKey() instanceof Fluid f && f != Fluids.EMPTY) {
            return RecipeShape.fluidAlt(BuiltInRegistries.FLUID.getKey(f), amount / unitsPerMilliBucket());
        }
        ItemStack is = s.getItemStack();
        return is.isEmpty() ? null : RecipeShape.altOf(is, amount);
    }

    /** EMI counts fluids in the loader's fluid unit, the same as JEI: this many units make a millibucket. */
    private static long unitsPerMilliBucket() {
        if (!Calculator.available()) return 1;
        return Math.max(1, Calculator.runtime.getJeiHelpers()
            .getPlatformFluidHelper()
            .bucketVolume() / 1000);
    }

    /** The EMI stack of an addon stack: a fluid for a fluid carrier, else the item. */
    public static EmiStack emiStackOf(ItemStack stack) {
        String fluid = FluidStacks.fluidIdOf(stack);
        if (fluid != null) {
            return BuiltInRegistries.FLUID.getOptional(new ResourceLocation(fluid))
                .map(f -> EmiStack.of(f))
                .orElse(EmiStack.EMPTY);
        }
        return EmiStack.of(stack);
    }

    /** EMI failing on one recipe leaves that recipe out: one warning for the first, the rest at debug level. */
    private static void recipeFailed(Throwable e) {
        if (!warnedRecipe) {
            warnedRecipe = true;
            NeiAddon.LOGGER.warn("Could not read an EMI recipe; it is left out (later ones are logged at debug level)", e);
        } else {
            NeiAddon.LOGGER.debug("Could not read an EMI recipe; it is left out", e);
        }
    }
}
