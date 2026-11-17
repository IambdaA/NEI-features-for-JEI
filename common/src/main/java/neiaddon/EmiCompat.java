package neiaddon;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.ClientTickEvent;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import neiaddon.platform.Platform;

/**
 * The addon's side of EMI, another recipe viewer on top of JEI. EMI is optional, so this class uses no EMI types: the
 * EMI plugin (in the emi package, the only code that touches EMI) sets {@link #hooks} and asks {@link #areas} for the
 * screen areas EMI keeps its sidebars out of. Under EMI the recipe lookups also ask EMI for the recipes JEI does not
 * have (see {@link #recipesFor} and {@link #find}).
 */
public final class EmiCompat {

    /** What the EMI plugin answers for the addon. */
    public interface Hooks {

        boolean cheatMode();

        /** Every EMI recipe that makes the stack (an item, or a fluid carrier), as shapes; empty when there are none. */
        List<RecipeShape> recipesFor(ItemStack stack);

        /** The EMI recipe with this addon recipe id, as a shape carrying recipeId and a handle, or null. hint = an item it makes. */
        Found find(String recipeId, ItemStack hint);

        /** EMI's picture of the EMI recipe with this addon recipe id (for a recipe JEI has no layout of), or null. hint = an item it makes. */
        Picture picture(String recipeId, ItemStack hint);

        /** Whether EMI has finished loading its recipes. */
        boolean loaded();

        /** The name of the EMI category with this id, or null when EMI has none. */
        String categoryName(String id);

        /** The icon of the EMI category with this id, or null when EMI has none. */
        IDrawable categoryIcon(String id);

        /** Drops the stack (an item, or a fluid carrier) on a target of the open screen through EMI's drag-and-drop handlers; true when one took it. */
        boolean dropStack(Screen screen, ItemStack stack, int x, int y);

        /** EMI's player-set default recipe of the item (a stack; a fluid carrier for a fluid), as the addon's recipe id; null when there is none. EMI's built-in defaults do not count. */
        String defaultRecipe(ItemStack item);

        /** The item id (see {@link FluidStacks}) whose EMI default recipe is the one with this addon recipe id, or null. */
        String defaultItemOf(String recipeId);

        /** Makes the EMI recipe with this addon recipe id the default of the item; false when EMI has no such recipe. hint = an item it makes. */
        boolean setDefault(ItemStack item, String recipeId, ItemStack hint);

        /** Removes EMI's player-set default recipe of the item, when it has the addon recipe id onlyRecipeId (any when null). */
        void clearDefault(ItemStack item, String onlyRecipeId);

        /** Removes the player-set default of every other output of the recipe (not keepItemId) that has this recipe. */
        void clearOtherDefaults(String recipeId, String keepItemId);

        /**
         * Once per client tick: the EMI favorites added since the last tick become bookmarks when panelShown (and are
         * removed from EMI's favorites); otherwise they stay EMI's. Favorites with a recipe context are left alone.
         */
        void favoritesTick(boolean panelShown);
    }

    /** One EMI recipe as a transfer target. Implementations never throw. */
    public interface RecipeHandle {
        /** Opens the recipe in EMI's recipe screen. */
        void show();

        /** EMI's category icon, or null. */
        IDrawable icon();

        /** Whether EMI can fill the open screen's grid with one craft now. */
        boolean canFill(AbstractContainerMenu menu);

        /** Fills the grid (max: as many crafts as fit); true when done. */
        boolean fill(AbstractContainerMenu menu, boolean max);

        /** Null when canFill, else a short reason for the player. */
        String whyNot(AbstractContainerMenu menu);

        /** The JEI recipe category this EMI recipe maps to, or null (also null for recipes built from EMI's own data). */
        IRecipeCategory<?> jeiCategory();

        /** The JEI recipe this EMI recipe maps to, or null (also null for recipes built from EMI's own data). */
        Object jeiRecipe();
    }

    /** An EMI recipe found for an addon recipe id: its shape, and the handle that shows and fills it. */
    public record Found(RecipeShape shape, RecipeHandle handle) {}

    /** EMI's drawing of one recipe (its box, border included), for a bookmark's tooltip. Implementations never throw. */
    public interface Picture {

        int width();

        int height();

        /** Draws the box with its top left corner at (x, y). */
        void draw(GuiGraphics g, int x, int y);

        /** True once drawing has failed; the picture then draws nothing and should not be shown. */
        boolean broken();
    }

    /**
     * The addon's buttons on EMI's recipe screen (see {@link #recipeScreen}). The screen is EMI's internal class, so the
     * hooks are called from the addon's own screen events, after the screen is drawn. Implementations never throw.
     */
    public interface RecipeScreenHooks {

        /** Draws the buttons over EMI's recipe screen (after the screen is drawn), with the mouse at this point. */
        void render(Screen screen, GuiGraphics g, int mouseX, int mouseY);

        /** A mouse press on EMI's recipe screen: true when one of the buttons took it (the click is then not EMI's). */
        boolean click(Screen screen, double mouseX, double mouseY, int button);
    }

    /**
     * The recipe search field on EMI's recipe screen: it narrows the recipes EMI shows (see {@link RecipeScreenHooks} for
     * the buttons). The hooks are called from the addon's own events, only on EMI's recipe screen. Implementations never throw.
     */
    public interface RecipeSearchHooks {

        /** Once per client tick, with the screen that is open: indexes and applies the search when it is due. */
        void tick(Screen screen);

        /** Draws the field and its button over EMI's recipe screen (after the screen is drawn). */
        void render(Screen screen, GuiGraphics g, int mouseX, int mouseY);

        /** A mouse press: true when the field or its button took it (the click is then not EMI's). */
        boolean click(Screen screen, double mouseX, double mouseY, int button);

        /** A key press: true when the focused field took it (the key is then not EMI's). */
        boolean key(Screen screen, int keyCode);

        /** A typed character: true when the focused field took it (the character is then not EMI's). */
        boolean typed(Screen screen, int codePoint);

        /** The screen is about to be replaced: its recipes go back to the whole lookup before EMI keeps it in its history. */
        void leaving(Screen screen);
    }

    /**
     * Where EMI has put its own widgets on the open screen, read from EMI's internals (see {@link #layout}), so the
     * addon's quantity field and buttons line up with them. Areas are in GUI coordinates; null when EMI shows none.
     */
    public interface LayoutHooks {

        /** EMI's item list (the item grid of the sidebar that holds the search results), or null. */
        Rect2i index();

        /** EMI's search box, or null when it is hidden. */
        Rect2i search();

        /** EMI's own buttons in the bottom-left corner (its settings and recipe tree buttons) that are shown, left to right. */
        List<Rect2i> buttons();
    }

    /** EMI's recipe screen, matched by its class name (EMI's class is not part of this addon's API). */
    private static final String EMI_RECIPE_SCREEN = "dev.emi.emi.screen.RecipeScreen";

    /** Set by the EMI plugin when EMI is installed; null otherwise. */
    public static volatile RecipeScreenHooks recipeScreen;

    /** Set by the EMI plugin when EMI is installed; null otherwise. */
    public static volatile RecipeSearchHooks recipeSearch;

    /** Set by the EMI plugin when EMI is installed; null otherwise. */
    public static volatile Hooks hooks;

    /** Set by the EMI plugin when EMI is installed; null otherwise, and for good after one failure. */
    public static volatile LayoutHooks layout;

    /**
     * Counts the EMI reloads: the EMI plugin calls {@link #reloaded} when one starts (EMI's loading thread) and
     * {@link #loading} when it sees one end (game thread). A lost increment between the two still changes the number.
     */
    private static volatile int generation;

    private static boolean failed;
    private static boolean recipesFailed;
    /** Whether EMI is installed, once known (see {@link #installed()}). */
    private static volatile Boolean installed;
    /** Whether EMI was loading at the last check (see {@link #loading}). */
    private static volatile boolean wasLoading;
    private static final boolean[] warnedAreas = new boolean[3];
    private static boolean warnedRender;
    private static boolean warnedClick;
    private static boolean warnedSearchTick;
    private static boolean warnedSearchRender;
    private static boolean warnedSearchClick;
    private static boolean warnedSearchKey;
    private static boolean warnedSearchTyped;
    private static boolean warnedSearchLeaving;

    private EmiCompat() {}

    /** Called when an EMI reload starts (by the EMI plugin) and when one is seen to end (by {@link #loading}). */
    public static void reloaded() {
        generation++;
    }

    /** How many times EMI has reloaded; a changed number means EMI's recipes may differ now. */
    public static int generation() {
        return generation;
    }

    public static boolean present() {
        return hooks != null;
    }

    /**
     * Whether EMI is installed, known from the start of the game (unlike {@link #present()}, which waits for EMI to load the
     * addon's plugin). EMI then shows its own item list instead of JEI's, so the features built on JEI's list stand aside.
     */
    public static boolean installed() {
        Boolean known = installed;
        if (known != null) return known;
        try {
            known = present() || Platform.get()
                .modDisplayName("emi")
                .isPresent();
        } catch (RuntimeException | LinkageError e) {
            // the loader's entry point has not run yet, or its mod list is not ready: ask again later
            return present();
        }
        installed = known;
        return known;
    }

    /** EMI's item list, or null (no EMI, no list shown, or the layout could not be read). */
    public static Rect2i indexArea() {
        LayoutHooks h = layout;
        if (h == null) return null;
        try {
            return h.index();
        } catch (RuntimeException | LinkageError e) {
            layoutFailed(e);
            return null;
        }
    }

    /** EMI's search box, or null (no EMI, hidden, or the layout could not be read). */
    public static Rect2i searchArea() {
        LayoutHooks h = layout;
        if (h == null) return null;
        try {
            return h.search();
        } catch (RuntimeException | LinkageError e) {
            layoutFailed(e);
            return null;
        }
    }

    /** EMI's bottom-left buttons that are shown, left to right; empty without EMI or when the layout could not be read. */
    public static List<Rect2i> buttonAreas() {
        LayoutHooks h = layout;
        if (h == null) return List.of();
        try {
            return h.buttons();
        } catch (RuntimeException | LinkageError e) {
            layoutFailed(e);
            return List.of();
        }
    }

    private static boolean defaultsFailed;

    /**
     * Whether EMI's default recipes can be read now: EMI is there, its recipes are loaded and the defaults have not failed.
     * Without it, an item with no EMI default may still have one, so the favorites file must not be trusted to be
     * out of date (see {@link Favorites}).
     */
    public static boolean defaultsReady() {
        return hooks != null && !defaultsFailed && !loading();
    }

    /** EMI's default recipe of the item, as the addon's recipe id; null when EMI has none (or is not ready). */
    public static String defaultRecipe(ItemStack item) {
        Hooks h = hooks;
        if (h == null || !defaultsReady()) return null;
        try {
            return h.defaultRecipe(item);
        } catch (RuntimeException | LinkageError e) {
            defaultsFailed(e);
            return null;
        }
    }

    /** The item id whose EMI default recipe is the one with this addon recipe id; null when there is none (or EMI is not ready). */
    public static String defaultItemOf(String recipeId) {
        Hooks h = hooks;
        if (h == null || !defaultsReady()) return null;
        try {
            return h.defaultItemOf(recipeId);
        } catch (RuntimeException | LinkageError e) {
            defaultsFailed(e);
            return null;
        }
    }

    /** Makes the EMI recipe with this addon id the default of the item; false when EMI has no such recipe or cannot take it. */
    public static boolean setDefault(ItemStack item, String recipeId, ItemStack hint) {
        Hooks h = hooks;
        if (h == null || defaultsFailed || loading()) return false;
        try {
            return h.setDefault(item, recipeId, hint);
        } catch (RuntimeException | LinkageError e) {
            defaultsFailed(e);
            return false;
        }
    }

    /** Removes EMI's player-set default of the item, when it is the one with this addon id (any when null). Not while EMI loads. */
    public static void clearDefault(ItemStack item, String onlyRecipeId) {
        Hooks h = hooks;
        if (h == null || defaultsFailed || loading()) return;
        try {
            h.clearDefault(item, onlyRecipeId);
        } catch (RuntimeException | LinkageError e) {
            defaultsFailed(e);
        }
    }

    /** Removes the player-set defaults of the recipe's other outputs (not keepItemId). Not while EMI loads. */
    public static void clearOtherDefaults(String recipeId, String keepItemId) {
        Hooks h = hooks;
        if (h == null || defaultsFailed || loading()) return;
        try {
            h.clearOtherDefaults(recipeId, keepItemId);
        } catch (RuntimeException | LinkageError e) {
            defaultsFailed(e);
        }
    }

    /** EMI's default recipes are not used from now on; one warning is logged. */
    private static void defaultsFailed(Throwable e) {
        if (!defaultsFailed) {
            defaultsFailed = true;
            NeiAddon.LOGGER.warn("Could not read or change EMI's default recipes; the favorites file is used alone", e);
        }
    }

    /** One warning, then the addon's widgets keep their own places next to EMI. */
    private static void layoutFailed(Throwable e) {
        layout = null;
        NeiAddon.LOGGER.warn("Could not read where EMI put its item list and buttons; the quantity field and the N/S buttons keep their own places", e);
    }

    /** EMI's cheat mode; false without EMI, and for good after one failure (one warning is logged). */
    public static boolean cheatMode() {
        Hooks h = hooks;
        if (h == null || failed) return false;
        try {
            return h.cheatMode();
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            NeiAddon.LOGGER.warn("Could not read EMI's cheat mode; it counts as off", e);
            return false;
        }
    }

    private static boolean dropFailed;

    /**
     * Drops a stack that was dragged out of the bookmark panel on a target of the open screen through EMI's own drag-and-drop
     * handlers (the slots other mods let EMI's item list drop on). False without EMI, when no handler takes it, and for good
     * after one failure (one warning is logged).
     */
    public static boolean dropStack(Screen screen, ItemStack stack, int x, int y) {
        Hooks h = hooks;
        if (h == null || dropFailed) return false;
        try {
            return h.dropStack(screen, stack, x, y);
        } catch (RuntimeException | LinkageError e) {
            dropFailed = true;
            NeiAddon.LOGGER.warn("Could not drop a bookmark through EMI's drag and drop; only JEI's drop targets are used", e);
            return false;
        }
    }

    private static boolean favoritesFailed;

    /**
     * Turns the EMI favorites added since the last tick into bookmarks while the bookmark panel is shown (see
     * {@link Hooks#favoritesTick}). Not while EMI loads. A failure turns it off for good (one warning is logged).
     */
    static void favoritesTick(boolean panelShown) {
        Hooks h = hooks;
        if (h == null || favoritesFailed || !recipesAvailable() || loading()) return;
        try {
            h.favoritesTick(panelShown);
        } catch (RuntimeException | LinkageError e) {
            favoritesFailed = true;
            NeiAddon.LOGGER.warn("Could not turn EMI favorites into bookmarks; A over EMI's lists makes EMI favorites again", e);
        }
    }

    /** Whether EMI's recipes can be read: EMI is there and reading them has not failed. */
    public static boolean recipesAvailable() {
        return hooks != null && !recipesFailed;
    }

    /**
     * Whether EMI is still loading its recipes (then the addon must not cache an answer yet). The end of a load counts as
     * a reload, so the caches made while it loaded are dropped.
     */
    public static boolean loading() {
        if (!recipesAvailable()) return false;
        try {
            boolean now = !hooks.loaded();
            if (wasLoading && !now) reloaded();
            wasLoading = now;
            return now;
        } catch (RuntimeException | LinkageError e) {
            recipesFailed(e);
            return false;
        }
    }

    /** Every EMI recipe that makes the stack, as shapes; null when EMI's recipes cannot be used right now. */
    public static List<RecipeShape> recipesFor(ItemStack stack) {
        if (!recipesAvailable() || loading()) return null;
        try {
            return hooks.recipesFor(stack);
        } catch (RuntimeException | LinkageError e) {
            recipesFailed(e);
            return null;
        }
    }

    /** The EMI recipe with this addon recipe id, or null (also when EMI's recipes cannot be used right now). */
    public static Found find(String recipeId, ItemStack hint) {
        if (!recipesAvailable() || loading()) return null;
        try {
            return hooks.find(recipeId, hint);
        } catch (RuntimeException | LinkageError e) {
            recipesFailed(e);
            return null;
        }
    }

    private static boolean pictureFailed;

    /** True once {@link #picture} has failed; pictures cached before that must not be shown any more. */
    public static boolean picturesFailed() {
        return pictureFailed;
    }

    /**
     * EMI's picture of the EMI recipe with this addon recipe id, or null when EMI has none or its recipes cannot be used right
     * now. A failure turns EMI's pictures off for good (one warning is logged).
     */
    public static Picture picture(String recipeId, ItemStack hint) {
        Hooks h = hooks;
        if (h == null || pictureFailed || !recipesAvailable() || loading()) return null;
        try {
            return h.picture(recipeId, hint);
        } catch (RuntimeException | LinkageError e) {
            pictureFailed = true;
            NeiAddon.LOGGER.warn("Could not draw an EMI recipe in the bookmark tooltip; EMI pictures are off from now on", e);
            return null;
        }
    }

    /** The name of the EMI category with this id, or null (also when EMI's recipes cannot be used right now). */
    public static String categoryName(String id) {
        if (!recipesAvailable() || loading()) return null;
        try {
            return hooks.categoryName(id);
        } catch (RuntimeException | LinkageError e) {
            recipesFailed(e);
            return null;
        }
    }

    /** The icon of the EMI category with this id, or null (also when EMI's recipes cannot be used right now). */
    public static IDrawable categoryIcon(String id) {
        if (!recipesAvailable() || loading()) return null;
        try {
            return hooks.categoryIcon(id);
        } catch (RuntimeException | LinkageError e) {
            recipesFailed(e);
            return null;
        }
    }

    /** Is {@code s} EMI's recipe screen? (By class name: EMI's class is not part of this addon's API.) */
    public static boolean isRecipeScreen(Screen s) {
        return s != null && s.getClass()
            .getName()
            .equals(EMI_RECIPE_SCREEN);
    }

    /**
     * Draws the addon's buttons over EMI's recipe screen, after the screen is drawn: the hearts, then the recipe search
     * (so the field and its tooltip are on top). One warning per kind if either fails.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!isRecipeScreen(screen)) return;
        RecipeScreenHooks hooks = recipeScreen;
        if (hooks != null) {
            try {
                hooks.render(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
            } catch (RuntimeException | LinkageError e) {
                if (!warnedRender) {
                    warnedRender = true;
                    NeiAddon.LOGGER.warn("Could not draw the favorite hearts on EMI's recipe screen", e);
                }
            }
        }
        RecipeSearchHooks search = recipeSearch;
        if (search != null) {
            try {
                search.render(screen, event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
            } catch (RuntimeException | LinkageError e) {
                if (!warnedSearchRender) {
                    warnedSearchRender = true;
                    NeiAddon.LOGGER.warn("Could not draw the recipe search on EMI's recipe screen", e);
                }
            }
        }
    }

    /**
     * A click on EMI's recipe screen goes to the recipe search first, then to the addon's hearts; one warning per kind if
     * either fails.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onClick(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!isRecipeScreen(screen)) return;
        RecipeSearchHooks search = recipeSearch;
        if (search != null) {
            try {
                if (search.click(screen, event.getMouseX(), event.getMouseY(), event.getButton())) {
                    event.setCanceled(true);
                    return;
                }
            } catch (RuntimeException | LinkageError e) {
                if (!warnedSearchClick) {
                    warnedSearchClick = true;
                    NeiAddon.LOGGER.warn("Could not click the recipe search on EMI's recipe screen", e);
                }
            }
        }
        RecipeScreenHooks hooks = recipeScreen;
        if (hooks == null) return;
        try {
            if (hooks.click(screen, event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
        } catch (RuntimeException | LinkageError e) {
            if (!warnedClick) {
                warnedClick = true;
                NeiAddon.LOGGER.warn("Could not click the favorite hearts on EMI's recipe screen", e);
            }
        }
    }

    /** A key on EMI's recipe screen goes to the focused recipe search field first; one warning if that fails. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        RecipeSearchHooks search = recipeSearch;
        if (search == null || !isRecipeScreen(screen)) return;
        try {
            if (search.key(screen, event.getKeyCode())) event.setCanceled(true);
        } catch (RuntimeException | LinkageError e) {
            if (!warnedSearchKey) {
                warnedSearchKey = true;
                NeiAddon.LOGGER.warn("Could not read a key for the recipe search on EMI's recipe screen", e);
            }
        }
    }

    /** A typed character on EMI's recipe screen goes to the focused recipe search field first; one warning if that fails. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onChar(ScreenEvent.CharacterTyped.Pre event) {
        Screen screen = event.getScreen();
        RecipeSearchHooks search = recipeSearch;
        if (search == null || !isRecipeScreen(screen)) return;
        try {
            if (search.typed(screen, event.getCodePoint())) event.setCanceled(true);
        } catch (RuntimeException | LinkageError e) {
            if (!warnedSearchTyped) {
                warnedSearchTyped = true;
                NeiAddon.LOGGER.warn("Could not read a typed character for the recipe search on EMI's recipe screen", e);
            }
        }
    }

    /** Once per client tick, on EMI's recipe screen: the recipe search applies its filter here (never while drawing). */
    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        // once per tick, so each item's EMI default is looked up at most once per frame (see Favorites.recipeFor)
        Favorites.clearEmiLookups();
        if (present()) {
            // EMI reads A over its lists first and makes a favorite; while the bookmark panel is shown that favorite
            // becomes a bookmark (isActive: drawn at its last frame, so not when the window has no room for it)
            Screen shown = McCompat.screen();
            favoritesTick(BookmarkPanel.supports(shown) && !BookmarkPanel.isHidden() && BookmarkPanel.isActive());
        }
        RecipeSearchHooks search = recipeSearch;
        if (search == null) return;
        Screen screen = McCompat.screen();
        if (!isRecipeScreen(screen)) return;
        try {
            search.tick(screen);
        } catch (RuntimeException | LinkageError e) {
            if (!warnedSearchTick) {
                warnedSearchTick = true;
                NeiAddon.LOGGER.warn("Could not update the recipe search on EMI's recipe screen", e);
            }
        }
    }

    /** EMI's recipe screen is about to be replaced: the recipe search gives its recipes back first. */
    @SubscribeEvent
    static void onOpening(ScreenEvent.Opening event) {
        Screen screen = event.getCurrentScreen();
        RecipeSearchHooks search = recipeSearch;
        if (search == null || !isRecipeScreen(screen)) return;
        try {
            search.leaving(screen);
        } catch (RuntimeException | LinkageError e) {
            if (!warnedSearchLeaving) {
                warnedSearchLeaving = true;
                NeiAddon.LOGGER.warn("Could not give back the recipes of the recipe search on EMI's recipe screen", e);
            }
        }
    }

    /**
     * Draws one of the addon's buttons (the vanilla-style button of the addon's panels) for the EMI package, which cannot
     * reach {@link BookmarkPanel}.
     */
    public static void button(GuiGraphics g, Font font, int x, int y, int w, int h, boolean over) {
        BookmarkPanel.button(g, font, x, y, w, h, "", over);
    }

    /** EMI's recipes are not used from now on; one warning is logged. */
    private static void recipesFailed(Throwable e) {
        if (!recipesFailed) {
            recipesFailed = true;
            NeiAddon.LOGGER.warn("Could not read EMI's recipes; the addon uses only JEI's from now on", e);
        }
    }

    /** The areas of the screen EMI must keep its sidebars out of. EMI calls this every frame, so it never throws. */
    public static List<Rect2i> areas(Screen screen) {
        List<Rect2i> out = new ArrayList<>();
        add(out, 0, "bookmark panel", () -> BookmarkPanel.emiArea(screen));
        add(out, 1, "quantity field", QuantityField::extraAreas);
        add(out, 2, "craftables panel", CraftablesPanel::extraAreas);
        return out;
    }

    /** One source of areas: a failure leaves out only that source and logs one warning for it. */
    private static void add(List<Rect2i> out, int source, String name, Supplier<List<Rect2i>> areas) {
        try {
            out.addAll(areas.get());
        } catch (RuntimeException | LinkageError e) {
            if (!warnedAreas[source]) {
                warnedAreas[source] = true;
                NeiAddon.LOGGER.warn("Could not work out where the {} is for EMI; EMI may cover it", name, e);
            }
        }
    }
}
