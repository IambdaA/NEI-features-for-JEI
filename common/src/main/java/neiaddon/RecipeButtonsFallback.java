package neiaddon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.category.extensions.IRecipeCategoryDecorator;
import mezz.jei.api.registration.IAdvancedRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import org.joml.Matrix4f;

/**
 * The favorite heart and the crafting tree's "+" on JEI versions without the recipe button API (JEI 15.20 in ATM9 and
 * Monifactory, 19.25 in ATM10). A recipe category decorator, which every JEI version here has, draws them to the right of
 * each recipe, where JEI puts its own recipe buttons; clicks and tooltips are handled here, and the hover tracking the
 * keys over recipes need is fed the same way JEI's buttons feed it.
 *
 * <p>
 * The decorator only gets the recipe, so a recipe layout of our own is made for each recipe (once) and moved to where JEI
 * draws the recipe (the drawing is translated to the recipe's corner).
 */
public final class RecipeButtonsFallback {

    private static final int SIZE = 11;
    private static final long FRESH_NANOS = 150_000_000L;
    private static boolean active;

    /** Recipe -> its buttons; weak, so recipes JEI drops after a reload go too. */
    private static final Map<Object, Entry> entries = new WeakHashMap<>();
    private static final List<Entry> live = new ArrayList<>();

    private static final class Entry {

        final IRecipeLayoutDrawable<?> layout;
        final RecipeHearts.Controller heart;
        TreeRecipes.Controller plus;
        Rect2i heartArea, plusArea;
        long seen;

        Entry(IRecipeLayoutDrawable<?> layout, RecipeHearts.Controller heart) {
            this.layout = layout;
            this.heart = heart;
        }
    }

    /** No layout for this recipe (kept so it is not tried every frame). */
    private static final Entry NONE = new Entry(null, null);

    private RecipeButtonsFallback() {}

    /** Does this JEI have the recipe button API ({@link JeiRecipeButtons})? Never loads a class that may be missing. */
    static boolean jeiHasButtons() {
        try {
            Class<?> factory = Class.forName("mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory");
            IAdvancedRegistration.class.getMethod("addRecipeButtonFactory", factory);
            Class.forName("mezz.jei.api.gui.buttons.IButtonState");
            Class.forName("mezz.jei.api.gui.buttons.IIconButtonController");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    static void register(IAdvancedRegistration registration) {
        int[] types = { 0 };
        registration.getJeiHelpers()
            .getAllRecipeTypes()
            .forEach(type -> {
                add(registration, type);
                types[0]++;
            });
        active = true;
        NeiAddon.LOGGER.info("This JEI has no recipe button API; the addon draws the favorite heart and the tree's + itself ({} recipe types)", types[0]);
    }

    private static <T> void add(IAdvancedRegistration registration, RecipeType<T> type) {
        registration.addRecipeCategoryDecorator(type, new IRecipeCategoryDecorator<T>() {

            @Override
            public void draw(T recipe, IRecipeCategory<T> category, IRecipeSlotsView slots, GuiGraphics g, double mouseX, double mouseY) {
                try {
                    RecipeButtonsFallback.draw(recipe, category, g, mouseX, mouseY);
                } catch (RuntimeException e) {
                    NeiAddon.LOGGER.debug("Could not draw the favorite heart of a recipe", e);
                }
            }
        });
    }

    private static <T> void draw(T recipe, IRecipeCategory<T> category, GuiGraphics g, double mouseX, double mouseY) {
        Entry e = entries.get(recipe);
        if (e == null) {
            e = make(recipe, category);
            entries.put(recipe, e);
        }
        if (e == NONE) return;
        // the decorator draws in the recipe's own coordinates: the translation is where JEI shows the recipe
        Matrix4f m = g.pose()
            .last()
            .pose();
        int ox = Math.round(m.m30());
        int oy = Math.round(m.m31());
        Rect2i rect = e.layout.getRect();
        if (rect.getX() != ox || rect.getY() != oy) e.layout.setPosition(ox, oy);
        int w = rect.getWidth();
        int mx = ox + (int) Math.floor(mouseX);
        int my = oy + (int) Math.floor(mouseY);
        e.seen = System.nanoTime();
        if (!live.contains(e)) live.add(e);
        if (e.heart != null && e.heart.shown()) {
            e.heartArea = new Rect2i(ox + w + 2, oy, SIZE, SIZE);
            IDrawable icon = e.heart.icon();
            icon.draw(g, w + 2 + (SIZE - icon.getWidth()) / 2, (SIZE - icon.getHeight()) / 2);
            e.heart.frame(e.heartArea, mx, my);
        } else {
            e.heartArea = null;
        }
        if (!TreeRecipes.waiting()) {
            e.plus = null;
        } else if (e.plus == null) {
            e.plus = TreeRecipes.create(e.layout);
        }
        if (e.plus != null) {
            e.plusArea = new Rect2i(ox + w + 2, oy + SIZE + 1, SIZE, SIZE);
            IDrawable icon = TreeRecipes.PLUS_ICON;
            icon.draw(g, w + 2 + (SIZE - icon.getWidth()) / 2, SIZE + 1 + (SIZE - icon.getHeight()) / 2);
        } else {
            e.plusArea = null;
        }
    }

    private static <T> Entry make(T recipe, IRecipeCategory<T> category) {
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null) return NONE;
        try {
            IRecipeLayoutDrawable<T> layout = rt.getRecipeManager()
                .createRecipeLayoutDrawable(
                    category,
                    recipe,
                    rt.getJeiHelpers()
                        .getFocusFactory()
                        .getEmptyFocusGroup())
                .orElse(null);
            if (layout == null) return NONE;
            // the hint of fluid results goes into the slot tooltips of the layout JEI shows, not of this copy
            return new Entry(layout, RecipeHearts.create(layout, false));
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.debug("Could not make the favorite heart of a recipe", e);
            return NONE;
        }
    }

    /** The button of a recipe drawn a moment ago that holds the point, as [entry, 0 = heart / 1 = "+"], or null. */
    private static Object[] at(double x, double y) {
        long now = System.nanoTime();
        live.removeIf(e -> now - e.seen > FRESH_NANOS);
        for (Entry e : live) {
            if (in(e.heartArea, x, y)) return new Object[] { e, 0 };
            if (in(e.plusArea, x, y)) return new Object[] { e, 1 };
        }
        return null;
    }

    private static boolean in(Rect2i r, double x, double y) {
        return r != null && x >= r.getX() && x < r.getX() + r.getWidth() && y >= r.getY() && y < r.getY() + r.getHeight();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!active || event.getButton() != 0 || !(event.getScreen() instanceof IRecipesGui)) return;
        Object[] hit = at(event.getMouseX(), event.getMouseY());
        if (hit == null) return;
        Entry e = (Entry) hit[0];
        boolean used = (Integer) hit[1] == 0 ? e.heart.press(false) : e.plus != null && e.plus.press(false);
        if (used) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        if (!active || !(event.getScreen() instanceof IRecipesGui)) return;
        Object[] hit = at(event.getMouseX(), event.getMouseY());
        if (hit == null) return;
        Entry e = (Entry) hit[0];
        List<Component> lines = (Integer) hit[1] == 0 ? e.heart.tooltipLines() : List.of(e.plus.tooltip());
        event.getGuiGraphics()
            .renderComponentTooltip(Minecraft.getInstance().font, lines, event.getMouseX(), event.getMouseY());
    }
}
