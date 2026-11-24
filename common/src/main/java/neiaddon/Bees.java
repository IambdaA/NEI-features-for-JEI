package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.TooltipFlag;

/**
 * A bee of Productive Bees (its JEI ingredient) as one of the addon's own bookmark entries, the way a fluid of JEI becomes
 * one through {@link FluidStacks}. Used for the bookmark key over a bee in JEI's item list or on one of its recipe pages,
 * and for drawing, tooltipping and opening the recipes of such an entry in the bookmark panel.
 *
 * <p>Productive Bees is optional and is not compiled against: everything here goes through reflection on its JEI classes
 * by name, and every call is wrapped, so with the mod absent (or when it changes these classes) there is no bee type to
 * find and every method of this class reports nothing, which is how the bookmark key behaves today. A bee is stored as
 * an entry with no item behind it, only its id, {@value #PREFIX} plus the id of the bee entity it stands for
 * ({@code bee:productivebees:agate_bee}); the panel draws, tooltips and asks JEI for the recipes of those entries through
 * this class, and the chain maths, giving, pulling, autocrafting and chat links, which all need an item, skip them.
 *
 * <p>The picture is the one JEI itself shows for that bee, {@link IGuiHelper#createDrawableIngredient} of Productive
 * Bees' own renderer, and the tooltip its own renderer writes. Bees are looked up in Productive Bees' factory list of
 * all its bees ({@code getOrCreateList}; {@code getRBeesIngredients} sounds like the same thing but is only the old
 * Resourceful Bees bridge), matched by the bee entity id and never by the keys of that map, which are not the same
 * thing. A bee that is no longer there keeps its slot, draws nothing and says the entity's own name.
 */
final class Bees {

    /** The id a bookmarked bee is stored under: {@code bee:} plus the id of the bee it stands for. */
    static final String PREFIX = "bee:";

    private static final String PLUGIN = "cy.jdkdigital.productivebees.compat.jei.ProductiveBeesJeiPlugin";
    private static final String FACTORY = "cy.jdkdigital.productivebees.compat.jei.ingredients.BeeIngredientFactory";
    private static final String RENDERER = "cy.jdkdigital.productivebees.compat.jei.ingredients.BeeIngredientRenderer";

    private static boolean warned;
    /** Has Productive Bees been looked for once? Its type stays null when the mod is not there, and is not looked for again. */
    private static boolean looked;
    /** Productive Bees' JEI ingredient type ({@code BEE_INGREDIENT}), null when it has none. */
    private static IIngredientType<?> type;
    /** The ingredient class's {@code getBeeType()}, which is what the id is built from. */
    private static Method beeType;
    /** An id and the bee Productive Bees has for it, looked up once; a null value means there is no such bee. */
    private static final Map<String, Object> ingredients = new HashMap<>();
    /** An id and how to draw its bee, made once; a null value means there is nothing to draw. */
    private static final Map<String, IDrawable> drawables = new HashMap<>();
    /** Productive Bees' bee renderer and its {@code getTooltip}, made once; null when they could not be had (see tooltipReady). */
    private static Object tooltipRenderer;
    private static Method tooltipMethod;
    /** Has the renderer been looked for? A failure leaves {@link #tooltipMethod} null, and it is not tried again. */
    private static boolean tooltipLooked;

    private Bees() {}

    /** JEI loaded again (or the game reloaded): every bee read from Productive Bees is stale now. */
    static void reset() {
        looked = false;
        type = null;
        beeType = null;
        ingredients.clear();
        drawables.clear();
        tooltipLooked = false;
        tooltipRenderer = null;
        tooltipMethod = null;
    }

    static boolean isBeeId(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /** The bookmark id of a Productive Bees ingredient, or null for anything that is not one. */
    static String idOf(Object ingredient) {
        IIngredientType<?> beeIngredient = type();
        if (beeIngredient == null || ingredient == null) return null;
        try {
            if (!beeIngredient.getIngredientClass()
                .isInstance(ingredient)) return null;
            Object bee = beeType.invoke(ingredient);
            return bee instanceof ResourceLocation rl ? PREFIX + rl : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce(e);
            return null;
        }
    }

    /** The id of the bee under the mouse in JEI's item list or in a recipe of its recipe screen, or null. */
    static String underMouse() {
        IIngredientType<?> beeIngredient = type();
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null || beeIngredient == null) return null;
        try {
            // like JeiFluids: the list's untyped lookup first (its typed one differs between JEI versions), then the
            // recipe screen's typed one, which is the same on every JEI this runs on
            Optional<ITypedIngredient<?>> listed = rt.getIngredientListOverlay()
                .getIngredientUnderMouse();
            if (listed.isPresent()) return idOf(listed.get()
                .getIngredient());
            Object hovered = rt.getRecipesGui()
                .getIngredientUnderMouse(beeIngredient)
                .orElse(null);
            return idOf(hovered);
        } catch (RuntimeException | LinkageError e) {
            warnOnce(e);
            return null;
        }
    }

    /** The bee's picture where an item's would be ({@code x}, {@code y} of a 16x16 box), as JEI draws it in its list. */
    static void draw(GuiGraphics g, String id, int x, int y) {
        IDrawable drawable;
        if (drawables.containsKey(id)) drawable = drawables.get(id);
        else {
            drawable = makeDrawable(id);
            drawables.put(id, drawable);
        }
        if (drawable == null) return;
        // a bee is drawn in a 16x16 box like an item, so this is the same spot; if a bee's renderer is ever a different
        // size, keep it in the middle of the box
        try {
            drawable.draw(g, x + (16 - drawable.getWidth()) / 2, y + (16 - drawable.getHeight()) / 2);
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    /** The recipes of this bee, in JEI's recipe screen, the way an item's recipes are shown (OUTPUT makes it, INPUT uses it). */
    static void showRecipes(String id, RecipeIngredientRole role) {
        Object ingredient = ingredient(id);
        IIngredientType<?> beeIngredient = type();
        IJeiRuntime rt = Calculator.runtime;
        if (ingredient == null || beeIngredient == null || rt == null) return;
        try {
            showFocused(rt, beeIngredient, ingredient, role);
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    /** What the bee's tooltip says: Productive Bees' own tooltip of that bee, or its entity's name when it is gone. */
    static List<Component> tooltip(String id) {
        Object ingredient = ingredient(id);
        if (ingredient != null && tooltipReady()) {
            try {
                Object lines = tooltipMethod.invoke(tooltipRenderer, ingredient, TooltipFlag.Default.NORMAL);
                if (lines instanceof List<?> list && !list.isEmpty()) {
                    List<Component> tip = new ArrayList<>();
                    for (Object line : list) if (line instanceof Component c) tip.add(c);
                    if (!tip.isEmpty()) return tip;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                tooltipMethod = null; // a renderer that failed once is not asked again every frame
                warnOnce(e);
            }
        }
        // the bee is gone (its mod was removed, or it changed its bees): say the name the entity would have
        ResourceLocation rl = parse(id);
        return rl == null ? List.of() : List.of(Component.translatable("entity." + rl.getNamespace() + "." + rl.getPath()));
    }

    // ---------------------------------------------------------------- reading Productive Bees

    /** The type Productive Bees registered with JEI, looked for once; null when the mod is not there. */
    private static IIngredientType<?> type() {
        if (looked) return type;
        looked = true;
        try {
            Field field = Class.forName(PLUGIN).getField("BEE_INGREDIENT");
            if (field.get(null) instanceof IIngredientType<?> t) {
                type = t;
                beeType = t.getIngredientClass()
                    .getMethod("getBeeType");
            }
        } catch (ClassNotFoundException e) {
            // no Productive Bees: bees are not part of this game, so nothing to say about it
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            type = null;
            warnOnce(e);
        }
        return type;
    }

    /** The renderer and its getTooltip, looked up once; false when they could not be had (warned once, not retried). */
    private static boolean tooltipReady() {
        if (tooltipLooked) return tooltipMethod != null;
        tooltipLooked = true;
        try {
            Class<?> rendererClass = Class.forName(RENDERER);
            tooltipRenderer = rendererClass.getDeclaredConstructor()
                .newInstance();
            tooltipMethod = rendererClass.getMethod("getTooltip", Object.class, TooltipFlag.class);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            tooltipRenderer = null;
            tooltipMethod = null;
            warnOnce(e);
        }
        return tooltipMethod != null;
    }

    /** The bee Productive Bees has for a bookmark id; null when it has none. Looked up once per id. */
    private static Object ingredient(String id) {
        if (ingredients.containsKey(id)) return ingredients.get(id);
        Object found = findIngredient(id);
        ingredients.put(id, found);
        return found;
    }

    private static Object findIngredient(String id) {
        IIngredientType<?> beeIngredient = type();
        if (beeIngredient == null) return null;
        try {
            // the factory's whole list. NOT getRBeesIngredients(): despite how it reads, that method removes every
            // entry whose key does not contain "resourcefulbees" (it is only for the old Resourceful Bees bridge),
            // so without that mod it is empty - which left every bookmarked bee undrawn and without a name, with no
            // warning, as first seen in ATM9 Expert on 2026-11-19.
            Object byName = Class.forName(FACTORY)
                .getMethod("getOrCreateList")
                .invoke(null);
            if (!(byName instanceof Map<?, ?> bees)) return null;
            for (Object bee : bees.values()) {
                if (!beeIngredient.getIngredientClass()
                    .isInstance(bee)) continue;
                Object entity = beeType.invoke(bee);
                if (entity instanceof ResourceLocation rl && (PREFIX + rl).equals(id)) return bee;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            warnOnce(e);
        }
        return null;
    }

    private static IDrawable makeDrawable(String id) {
        IIngredientType<?> beeIngredient = type();
        IJeiRuntime rt = Calculator.runtime;
        Object ingredient = ingredient(id);
        if (beeIngredient == null || rt == null || ingredient == null) return null;
        try {
            return drawable(rt.getJeiHelpers()
                .getGuiHelper(), beeIngredient, ingredient);
        } catch (RuntimeException e) {
            warnOnce(e);
            return null;
        }
    }

    /** A focus on the bee and JEI's recipe screen of it, with a type that is only known at run time (checked first). */
    @SuppressWarnings("unchecked")
    private static <V> void showFocused(IJeiRuntime rt, IIngredientType<V> beeIngredient, Object ingredient,
        RecipeIngredientRole role) {
        IFocus<?> focus = rt.getJeiHelpers()
            .getFocusFactory()
            .createFocus(role, beeIngredient, (V) ingredient);
        rt.getRecipesGui()
            .show(List.of(focus));
    }

    /** {@code createDrawableIngredient} with a type that is only known at run time (the ingredient is checked first). */
    @SuppressWarnings("unchecked")
    private static <V> IDrawable drawable(IGuiHelper gui, IIngredientType<V> beeIngredient, Object ingredient) {
        return gui.createDrawableIngredient(beeIngredient, (V) ingredient);
    }

    private static ResourceLocation parse(String id) {
        try {
            return ResourceLocation.parse(id.substring(PREFIX.length()));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void warnOnce(Throwable e) {
        if (warned) return;
        warned = true;
        NeiAddon.LOGGER.warn("Could not use Productive Bees' bees; the bookmark panel shows such a bookmark without its picture or tooltip", e);
    }
}
