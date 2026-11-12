package neiaddon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.ingredient.IRecipeSlotRichTooltipCallback;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;

/**
 * The heart (favorite) button on every JEI recipe, like NEI's {@code GuiFavoriteButton}.
 *
 * <ul>
 * <li>Click the heart: this recipe becomes the favorite recipe for its result item (one favorite per item). Red heart =
 * favorite. The mouse wheel over the heart picks which result of a multi-result recipe the favorite is for.</li>
 * <li>Shift + bookmark key over the heart: saves the recipe tree as one new bookmark group (todo-list view, crafting
 * chain on). Shift + the tree key also opens the calculator for it.</li>
 * <li>Shift + bookmark key over a recipe: bookmarks / un-bookmarks just that recipe in the default group.</li>
 * </ul>
 *
 * <p>
 * JEI's {@code addRecipeButtonFactory} hands each recipe layout to {@link Factory}. The key press is not delivered to a
 * button, so each button reports the mouse position from its per-frame draw callback and the key handler asks which heart
 * or recipe was hovered a moment ago.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class RecipeHearts {

    private static final String JEI_RECIPES_GUI = "mezz.jei.gui.recipes.RecipesGui";
    private static final long FRESH_NANOS = 150_000_000L;

    /** The recipes of the open recipe screen, as their hearts report them every frame (for the highlight of owned items). */
    private static final List<Controller> visible = new ArrayList<>();
    private static Controller hoverHeart;
    private static Controller hoverView;
    private static long heartStamp;
    private static long viewStamp;
    private static int hoverX, hoverY;

    private RecipeHearts() {}

    public static final class Factory implements IRecipeButtonControllerFactory {

        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
            try {
                return new Controller(new JeiRecipeView(layout));
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.warn("Could not create the favorite button for a recipe", e);
                return null;
            }
        }
    }

    /** The heart of a recipe view, or null when it cannot be made. For a recipe viewer other than JEI's (no hints added). */
    public static Controller create(RecipeView view) {
        try {
            return new Controller(view);
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not create the favorite button for a recipe", e);
            return null;
        }
    }

    // ---------------------------------------------------------------- the icon

    private static final String[] HEART = { ".XX...XX.", "XXXX.XXXX", "XXXXXXXXX", "XXXXXXXXX", ".XXXXXXX.", "..XXXXX..",
        "...XXX...", "....X...." };

    private static final class HeartIcon implements IDrawable {

        private final int base;
        private final int light;

        HeartIcon(int base, int light) {
            this.base = base;
            this.light = light;
        }

        @Override
        public int getWidth() {
            return 9;
        }

        @Override
        public int getHeight() {
            return 8;
        }

        @Override
        public void draw(GuiGraphicsExtractor g, int xOffset, int yOffset) {
            for (int row = 0; row < HEART.length; row++) {
                String line = HEART[row];
                int col = 0;
                while (col < line.length()) {
                    if (line.charAt(col) != 'X') {
                        col++;
                        continue;
                    }
                    int end = col;
                    while (end < line.length() && line.charAt(end) == 'X') end++;
                    g.fill(xOffset + col, yOffset + row, xOffset + end, yOffset + row + 1, base);
                    col = end;
                }
            }
            // a little shine on the upper left lobe
            g.fill(xOffset + 1, yOffset + 1, xOffset + 3, yOffset + 2, light);
        }
    }

    private static final IDrawable ICON_OFF = new HeartIcon(0xFF4A4A4A, 0xFF7C7C7C);
    private static final IDrawable ICON_ON = new HeartIcon(0xFFD81E1E, 0xFFFF8A8A);

    // ---------------------------------------------------------------- the button

    public static final class Controller implements IIconButtonController {

        private final RecipeView view;
        final RecipeShape shape;
        private final List<String> resultIds = new ArrayList<>();
        private int selected;
        /** When the screen last drew this recipe (see {@link #visible}). */
        private long seen;
        /** True when the hint of fluid result slots is part of JEI's own tooltip (see {@link #injectHints}). */
        private boolean hintsInjected;

        Controller(RecipeView view) {
            this.view = view;
            this.shape = view.shape();
            RecipeLookup.register(shape);
            for (RecipeData.Part part : shape.toData(null).results) resultIds.add(part.itemId());
            String favorite = Favorites.itemFor(shape.id);
            int at = favorite == null ? -1 : resultIds.indexOf(favorite);
            selected = Math.max(0, at);
            hintsInjected = view instanceof JeiRecipeView jei && injectHints(jei.layout());
        }

        /**
         * JEI has no event for the tooltip of a fluid, only the callbacks a recipe's own slots were built with. Add one to
         * each result slot so the hotkey hint is a line of that tooltip. This reaches into JEI's slot class; when that
         * does not work the hint is drawn in a box of its own instead ({@link #onRenderHint}).
         */
        @SuppressWarnings("unchecked")
        private boolean injectHints(IRecipeLayoutDrawable<?> layout) {
            IRecipeSlotRichTooltipCallback callback = (slot, tooltip) -> {
                if (slot.getRole() != RecipeIngredientRole.OUTPUT || !usable()) return;
                List<RecipeShape.Alt> alts = RecipeShape.altsOf(slot);
                if (alts.isEmpty()) return;
                String result = alts.get(0)
                    .id();
                // items get theirs from the item tooltip event
                if (!FluidStacks.isFluidId(result) || !resultIds.contains(result)) return;
                for (String line : resultHint(this, result)) tooltip.add(Component.literal(line));
            };
            boolean any = false;
            try {
                for (IRecipeSlotView slot : layout.getRecipeSlotsView()
                    .getSlotViews()) {
                    if (slot.getRole() != RecipeIngredientRole.OUTPUT) continue;
                    any = true;
                    java.lang.reflect.Field field = slot.getClass()
                        .getDeclaredField("tooltipCallbacks");
                    field.setAccessible(true);
                    ((List<IRecipeSlotRichTooltipCallback>) field.get(slot)).add(callback);
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                NeiAddon.LOGGER.debug("Could not add the hotkey hint to JEI's slot tooltips", e);
                return false;
            }
            return any;
        }

        private boolean usable() {
            return !resultIds.isEmpty() && !shape.inputs.isEmpty();
        }

        private static boolean scrolls() {
            return AddonConfig.HEART_SCROLL_RESULT.get();
        }

        private boolean isOn() {
            String favorite = Favorites.itemFor(shape.id);
            return favorite != null && !resultIds.isEmpty() && favorite.equals(resultIds.get(selected));
        }

        void cycle(int dir) {
            if (resultIds.size() <= 1) return;
            selected = Math.floorMod(selected - dir, resultIds.size());
        }

        void toggle() {
            if (!usable()) return;
            boolean off = isOn();
            NeiAddon.LOGGER.info("Favorite {} -> {} (results {}, selected {})", resultIds.get(selected), off ? "removed" : shape.id, resultIds, selected);
            Favorites.set(resultIds.get(selected), off ? null : shape.id);
        }

        @Override
        public void initState(IButtonState state) {
            if (!usable()) {
                state.setActive(false);
                state.setVisible(false);
            }
            updateState(state);
        }

        @Override
        public void updateState(IButtonState state) {
            state.setIcon(isOn() ? ICON_ON : ICON_OFF);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            if (!usable()) return false;
            if (!input.isSimulate()) toggle();
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable(isOn() ? "neiaddon.recipe.unfavorite" : "neiaddon.recipe.favorite"));
            if (resultIds.size() > 1) {
                tooltip.add(Component.literal("§7" + nameOf(resultIds.get(selected))));
                if (!scrolls()) {
                    tooltip.add(Component.literal("§7Shift+" + CalcKeys.FAVORITE.getTranslatedKeyMessage()
                        .getString() + " over a result: favorite that one"));
                } else {
                    tooltip.add(Component.literal("§7Mouse wheel: Change result"));
                }
            }
            String key = CalcKeys.ADD.getTranslatedKeyMessage()
                .getString();
            tooltip.add(Component.literal("§7Shift+" + key + ": Bookmark Recipe Tree"));
        }

        @Override
        public void drawExtras(GuiGraphicsExtractor g, Rect2i buttonArea, int mouseX, int mouseY, float partialTicks) {
            long now = System.nanoTime();
            seen = now;
            if (!visible.contains(this)) visible.add(this);
            if (mouseX >= buttonArea.getX() && mouseX < buttonArea.getX() + buttonArea.getWidth()
                && mouseY >= buttonArea.getY() && mouseY < buttonArea.getY() + buttonArea.getHeight()) {
                hoverHeart = this;
                heartStamp = now;
                hoverX = mouseX;
                hoverY = mouseY;
            } else if (hoverHeart == this) {
                hoverHeart = null;
            }
            if (view.isMouseOver(mouseX, mouseY)) {
                hoverView = this;
                viewStamp = now;
                hoverX = mouseX;
                hoverY = mouseY;
            } else if (hoverView == this) {
                hoverView = null;
            }
        }

        /**
         * Tints the input slots whose ingredient the inventory holds (NEI's highlight of present items). A slot that
         * accepts several items counts when any of them is held.
         */
        void drawPresent(GuiGraphicsExtractor g, Map<String, Long> have) {
            for (RecipeView.Input input : view.inputs()) {
                boolean present = false;
                for (RecipeShape.Alt alt : input.alts()) {
                    Long held = have.get(alt.id());
                    if (held != null && held >= alt.count()) {
                        present = true;
                        break;
                    }
                }
                if (!present) continue;
                Rect2i area = input.area();
                int x = area.getX();
                int y = area.getY();
                int w = area.getWidth();
                int h = area.getHeight();
                g.fill(x, y, x + w, y + h, PRESENT_COLOR);
            }
        }

        /** Everything the result slot under the mouse accepts, or an empty list when the mouse is not over a result slot. */
        List<RecipeShape.Alt> hoveredResultAlts() {
            return view.altsAt(hoverX, hoverY, true);
        }

        /** Everything the slot under the mouse accepts (inputs too), or an empty list. */
        List<RecipeShape.Alt> slotAlts() {
            return view.altsAt(hoverX, hoverY, false);
        }

        /** The result under the mouse if the mouse is over a result slot, else null. */
        String hoveredResultId() {
            List<RecipeShape.Alt> alts = hoveredResultAlts();
            return alts.isEmpty() ? null : alts.get(0)
                .id();
        }
    }

    /** Everything the recipe slot under the mouse accepts, or an empty list (the ingredient directory). */
    static List<RecipeShape.Alt> hoveredSlotAlts() {
        Controller recipe = fresh(hoverView, viewStamp);
        return recipe == null ? List.of() : recipe.slotAlts();
    }

    /** Translucent green over an ingredient that is in the inventory. */
    private static final int PRESENT_COLOR = 0x6000C800;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRenderPresent(ScreenEvent.Render.Post event) {
        long now = System.nanoTime();
        visible.removeIf(c -> now - c.seen > FRESH_NANOS);
        if (visible.isEmpty() || !AddonConfig.HIGHLIGHT_PRESENT.get() || !onRecipesScreen()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Map<String, Long> have = new HashMap<>();
        for (net.minecraft.world.inventory.Slot slot : mc.player.containerMenu.slots) {
            if (slot.hasItem() && !CraftSlots.isResult(slot)) {
                have.merge(RecipeData.idOf(slot.getItem()), (long) slot.getItem()
                    .getCount(), Long::sum);
            }
        }
        for (Controller c : visible) {
            try {
                c.drawPresent(event.getGuiGraphics(), have);
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.debug("Could not highlight the items of a recipe", e);
            }
        }
    }

    private static String nameOf(String itemId) {
        ItemStack stack = FluidStacks.stackOf(itemId);
        return stack.isEmpty() ? itemId : FluidStacks.name(stack)
            .getString();
    }

    // ---------------------------------------------------------------- keys

    static boolean onRecipesScreen() {
        return isRecipesScreen(McCompat.screen());
    }

    /** Is {@code s} JEI's recipe screen? (Matched by class name: JEI's class is not part of this addon's API.) */
    public static boolean isRecipesScreen(Screen s) {
        return s != null && s.getClass()
            .getName()
            .equals(JEI_RECIPES_GUI);
    }

    private static Controller fresh(Controller c, long stamp) {
        return c != null && System.nanoTime() - stamp < FRESH_NANOS && onRecipesScreen() ? c : null;
    }

    /** Is the mouse over a heart or a recipe of the JEI recipe screen right now? */
    public static boolean hovering() {
        return fresh(hoverHeart, heartStamp) != null || fresh(hoverView, viewStamp) != null;
    }

    /**
     * Shift + bookmark key. Over a heart: saves the recipe tree as a new crafting group. Over a recipe: toggles that
     * recipe in the default group. Returns true if the key was used.
     */
    public static boolean onShiftBookmarkKey() {
        Controller heart = fresh(hoverHeart, heartStamp);
        if (heart != null && heart.usable()) {
            RecipeTree.save(heart.shape);
            return true;
        }
        Controller recipe = fresh(hoverView, viewStamp);
        if (recipe != null && recipe.usable()) {
            toggleRecipe(recipe);
            return true;
        }
        return false;
    }

    private static void toggleRecipe(Controller recipe) {
        Bookmarks.Page page = Bookmarks.page();
        RecipeData data = recipe.shape.toData(recipe.hoveredResultId());
        // NEI: if the recipe is already bookmarked in the default group the key removes it, else it adds it
        if (!Bookmarks.removeRecipe(page, data.recipeId, Bookmarks.NO_GROUP)) {
            if (Bookmarks.addRecipe(page, data, startRuns(), Bookmarks.NO_GROUP)) {
                page.normalize();
                Bookmarks.save();
            }
        }
    }

    /**
     * The bookmarks key over a result slot of a recipe in JEI's recipe screen, with "Bookmark Output as Recipe" on (JEI's
     * option when this JEI has it, else ours): bookmarks the whole recipe, or removes it. Returns true if the key was used.
     */
    public static boolean onBookmarkOutputKey(net.minecraft.world.item.ItemStack stack) {
        Boolean jei = JeiButtons.bookmarkOutputAsRecipe();
        // JEI's own option decides; a JEI without it (15.20) bookmarks the item, so this does nothing there
        if (!Boolean.TRUE.equals(jei)) return false;
        Controller recipe = fresh(hoverView, viewStamp);
        if (recipe == null || !recipe.usable() || recipe.hoveredResultId() == null || stack.isEmpty()) return false;
        // what JEI's own bookmark key makes with this option: the output item, carrying the recipe (recipe type icon in
        // its corner, the recipe on Shift), the same bookmark JEI's recipe bookmark button gives; pressed again it goes
        Bookmarks.toggleJeiRecipe(stack, recipe.shape.id);
        return true;
    }

    /**
     * How often a recipe saved with the bookmark key is set to run (NEI bookmarkRecipeWithCount): once when the option
     * is on or Ctrl is held, otherwise it has no count yet (0).
     */
    static long startRuns() {
        return AddonConfig.BM_RECIPE_WITH_COUNT.get() || Minecraft.getInstance()
            .hasControlDown() ? 1 : 0;
    }

    /** The id of the recipe the mouse is over in JEI's recipe screen (NEI's focused recipe), or null. */
    public static String hoveredRecipeId() {
        Controller recipe = fresh(hoverView, viewStamp);
        return recipe != null && recipe.usable() ? recipe.shape.id : null;
    }

    /**
     * Shift + favorite key over a result slot of a recipe in JEI's recipe screen: makes the recipe the favorite of that
     * result, or removes it when it already is. Returns true if the key was used.
     */
    public static boolean onFavoriteResultKey() {
        Controller recipe = fresh(hoverView, viewStamp);
        if (recipe == null || !recipe.usable()) return false;
        String result = recipe.hoveredResultId();
        if (result == null || !recipe.resultIds.contains(result)) return false;
        boolean on = recipe.shape.id.equals(Favorites.recipeFor(result));
        NeiAddon.LOGGER.info("Favorite {} -> {} (hovered result)", result, on ? "removed" : recipe.shape.id);
        Favorites.set(result, on ? null : recipe.shape.id);
        recipe.selected = recipe.resultIds.indexOf(result);
        return true;
    }

    /**
     * NEI's overlay key (gui.overlay, S) over a recipe of the recipe screen: moves the recipe's ingredients into the open
     * crafting grid, like JEI's "+" button. Shift moves as many crafts as the grid takes. Returns true if the key was
     * used.
     */
    public static boolean onFillKey(boolean max) {
        Controller recipe = fresh(hoverView, viewStamp);
        if (recipe == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        try {
            boolean done = recipe.view.transfer(mc.player.containerMenu, mc.player, max);
            NeiAddon.LOGGER.info("Fill key over recipe {}: {}", recipe.shape.id, done ? "moved" : "refused");
            return true;
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not fill the crafting grid with a recipe", e);
            return true;
        }
    }

    public static boolean hasFavorite(ItemStack stack) {
        return Favorites.recipeFor(RecipeData.idOf(stack), stack) != null;
    }

    static RecipeShape favoriteShape(ItemStack stack) {
        String recipeId = Favorites.recipeFor(RecipeData.idOf(stack), stack);
        return recipeId == null ? null : RecipeLookup.find(recipeId, stack);
    }

    /**
     * Shift + bookmark key over an item of JEI's list: toggles the item's favorite recipe in the default group, with its
     * count when {@link AddonConfig#BM_RECIPE_WITH_COUNT} is on or Ctrl is held (NEI saveRecipeInBookmark with saveIngredients).
     */
    public static boolean onShiftListItem(ItemStack stack) {
        RecipeShape shape = favoriteShape(stack);
        if (shape == null) return false;
        Bookmarks.Page page = Bookmarks.page();
        RecipeData data = shape.toData(RecipeData.idOf(stack));
        if (!Bookmarks.removeRecipe(page, data.recipeId, Bookmarks.NO_GROUP)) {
            if (Bookmarks.addRecipe(page, data, startRuns(), Bookmarks.NO_GROUP)) {
                page.normalize();
                Bookmarks.save();
            }
        }
        return true;
    }

    /**
     * NEI getFocusedRecipe: the recipe under the mouse in the recipe screen, else (over JEI's list) the item's favorite
     * recipe. Null when there is none.
     */
    public static RecipeData sharedRecipe(ItemStack stack, boolean useFavorites) {
        Controller recipe = fresh(hoverView, viewStamp);
        if (recipe != null && recipe.usable()) return recipe.shape.toData(recipe.hoveredResultId());
        Controller heart = fresh(hoverHeart, heartStamp);
        if (heart != null && heart.usable()) return heart.shape.toData(RecipeData.idOf(stack));
        if (!useFavorites) return null;
        RecipeShape shape = favoriteShape(stack);
        return shape == null ? null : shape.toData(RecipeData.idOf(stack));
    }

    /** Shift + craft key over an item that has a favorite recipe: autocrafts one run of it (NEI runAutoCrafting). */
    public static boolean autocraftFavorite(ItemStack stack, boolean ignoreInventory) {
        RecipeShape shape = favoriteShape(stack);
        NeiAddon.LOGGER.info("Autocrafting key over {}: favorite recipe found={}", RecipeData.idOf(stack), shape != null);
        if (shape == null) return false;
        return AutoCraft.start(Bookmarks.autocraftRecipe(shape.toData(RecipeData.idOf(stack))), ignoreInventory);
    }

    /** Shift + favorite key over an item of JEI's list: saves the favorite recipe's tree (NEI saveFavoriteTree). */
    public static void onShiftFavoriteKey(ItemStack stack) {
        RecipeShape shape = favoriteShape(stack);
        if (shape != null) RecipeTree.save(shape);
    }

    /**
     * Shift + tree key over a heart: saves the recipe tree and opens the calculator for the new group.
     */
    public static boolean onShiftTreeKey() {
        Controller heart = fresh(hoverHeart, heartStamp);
        if (heart == null || !heart.usable()) return false;
        int gid = RecipeTree.save(heart.shape);
        if (gid != Bookmarks.NO_GROUP && Calculator.available()) {
            CraftingTreeScreen.open(Bookmarks.page(), gid);
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        Controller heart = fresh(hoverHeart, heartStamp);
        if (heart == null || heart.resultIds.size() <= 1 || !Controller.scrolls()) return;
        int dir = (int) Math.signum(event.getScrollDeltaY());
        if (dir == 0) return;
        heart.cycle(dir);
        event.setCanceled(true);
    }

    /** The hotkey lines for a result slot: the favorite key, as the hint or (with Alt held) the list. */
    private static List<String> resultHint(Controller recipe, String result) {
        boolean on = recipe.shape.id.equals(Favorites.recipeFor(result));
        java.util.Map<String, String> keys = new java.util.HashMap<>();
        keys.put(HotkeyTips.keys("SHIFT", CalcKeys.FAVORITE.getTranslatedKeyMessage()
            .getString()), on ? "Remove Favorite Recipe" : "Favorite Recipe for this Result");
        return HotkeyTips.hotkeyLines(keys);
    }

    /**
     * The lines to add to the tooltip of an item while the mouse is over a result slot of a recipe in JEI's recipe screen
     * ("Hold ALT for hotkeys", or with Alt the keys), so the hint is part of JEI's own tooltip. Empty anywhere else. A
     * fluid has no item tooltip event; {@link #onRenderHint} covers it.
     */
    static List<String> resultTooltipLines(ItemStack stack) {
        if (!onRecipesScreen()) return List.of();
        Controller recipe = fresh(hoverView, viewStamp);
        if (recipe == null || !recipe.usable()) return List.of();
        List<RecipeShape.Alt> alts = recipe.hoveredResultAlts();
        if (alts.isEmpty()) return List.of();
        String shown = RecipeData.idOf(stack);
        boolean hovered = false;
        for (RecipeShape.Alt alt : alts) {
            if (alt.id()
                .equals(shown)) {
                hovered = true;
                break;
            }
        }
        String result = alts.get(0)
            .id();
        if (!hovered || !recipe.resultIds.contains(result)) return List.of();
        return resultHint(recipe, result);
    }

    /**
     * The same hint for a fluid result slot, in a small box beside the cursor: JEI's fluid tooltip cannot be added to, so
     * this is drawn next to it, on the left of the cursor. Items get theirs inside the tooltip ({@link #resultTooltipLines}).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRenderHint(ScreenEvent.Render.Post event) {
        if (!onRecipesScreen()) return;
        Controller recipe = fresh(hoverView, viewStamp);
        if (recipe == null || !recipe.usable() || recipe.hintsInjected) return;
        if (Math.abs(event.getMouseX() - hoverX) > 1 || Math.abs(event.getMouseY() - hoverY) > 1) return;
        String result = recipe.hoveredResultId();
        if (result == null || !FluidStacks.isFluidId(result) || !recipe.resultIds.contains(result)) return;
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        for (String line : resultHint(recipe, result)) {
            lines.add(Component.literal(line)
                .getVisualOrderText());
        }
        if (lines.isEmpty()) return;
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        int width = 0;
        for (net.minecraft.util.FormattedCharSequence line : lines) width = Math.max(width, font.width(line));
        // TreeDraw puts the box at mouse + 12, or at mouse - 12 - width when there is no room: ask for the left side
        TreeDraw.tooltip(event.getGuiGraphics(), font, event.getMouseX() - 24 - width, event.getMouseY(), event.getScreen().width,
            event.getScreen().height, lines, List.of(), 0, 0, null);
    }
}
