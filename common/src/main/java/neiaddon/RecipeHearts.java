package neiaddon;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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

    private static Controller hoverHeart;
    private static Controller hoverLayout;
    private static long heartStamp;
    private static long layoutStamp;
    private static int hoverX, hoverY;

    private RecipeHearts() {}

    public static final class Factory implements IRecipeButtonControllerFactory {

        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
            try {
                return new Controller(layout);
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.warn("Could not create the favorite button for a recipe", e);
                return null;
            }
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
        public void draw(GuiGraphics g, int xOffset, int yOffset) {
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

        private final IRecipeLayoutDrawable<?> layout;
        final RecipeShape shape;
        private final List<String> resultIds = new ArrayList<>();
        private int selected;

        Controller(IRecipeLayoutDrawable<?> layout) {
            this.layout = layout;
            this.shape = RecipeShape.read(layout);
            RecipeLookup.register(shape);
            for (RecipeData.Part part : shape.toData(null).results) resultIds.add(part.itemId());
            String favorite = Favorites.itemFor(shape.id);
            int at = favorite == null ? -1 : resultIds.indexOf(favorite);
            selected = Math.max(0, at);
        }

        private boolean usable() {
            return !resultIds.isEmpty() && !shape.inputs.isEmpty();
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
            Favorites.set(resultIds.get(selected), isOn() ? null : shape.id);
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
                tooltip.add(Component.literal("§7Mouse wheel: Change result"));
            }
            String key = CalcKeys.ADD.getTranslatedKeyMessage()
                .getString();
            tooltip.add(Component.literal("§7Shift+" + key + ": Bookmark Recipe Tree"));
        }

        @Override
        public void drawExtras(GuiGraphics g, Rect2i buttonArea, int mouseX, int mouseY, float partialTicks) {
            long now = System.nanoTime();
            if (mouseX >= buttonArea.getX() && mouseX < buttonArea.getX() + buttonArea.getWidth()
                && mouseY >= buttonArea.getY() && mouseY < buttonArea.getY() + buttonArea.getHeight()) {
                hoverHeart = this;
                heartStamp = now;
                hoverX = mouseX;
                hoverY = mouseY;
            } else if (hoverHeart == this) {
                hoverHeart = null;
            }
            if (layout.isMouseOver(mouseX, mouseY)) {
                hoverLayout = this;
                layoutStamp = now;
                hoverX = mouseX;
                hoverY = mouseY;
            } else if (hoverLayout == this) {
                hoverLayout = null;
            }
        }

        /** The result under the mouse if the mouse is over a result slot, else null. */
        String hoveredResultId() {
            Optional<RecipeSlotUnderMouse> under = layout.getSlotUnderMouse(hoverX, hoverY);
            if (under.isPresent() && under.get()
                .slot()
                .getRole() == RecipeIngredientRole.OUTPUT) {
                Optional<ItemStack> stack = under.get()
                    .slot()
                    .getItemStacks()
                    .filter(s -> !s.isEmpty())
                    .findFirst();
                if (stack.isPresent()) return RecipeData.idOf(stack.get());
            }
            return null;
        }
    }

    private static String nameOf(String itemId) {
        try {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId))
                .map(i -> i.getDefaultInstance()
                    .getHoverName()
                    .getString())
                .orElse(itemId);
        } catch (RuntimeException e) {
            return itemId;
        }
    }

    // ---------------------------------------------------------------- keys

    private static boolean onRecipesScreen() {
        Screen s = McCompat.screen();
        return s != null && s.getClass()
            .getName()
            .equals(JEI_RECIPES_GUI);
    }

    private static Controller fresh(Controller c, long stamp) {
        return c != null && System.nanoTime() - stamp < FRESH_NANOS && onRecipesScreen() ? c : null;
    }

    /** Is the mouse over a heart or a recipe of the JEI recipe screen right now? */
    public static boolean hovering() {
        return fresh(hoverHeart, heartStamp) != null || fresh(hoverLayout, layoutStamp) != null;
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
        Controller recipe = fresh(hoverLayout, layoutStamp);
        if (recipe != null && recipe.usable()) {
            Bookmarks.Page page = Bookmarks.page();
            RecipeData data = recipe.shape.toData(recipe.hoveredResultId());
            // NEI: if the recipe is already bookmarked in the default group the key removes it, else it adds it
            if (!Bookmarks.removeRecipe(page, data.recipeId, Bookmarks.NO_GROUP)) {
                if (Bookmarks.addRecipe(page, data, 1, Bookmarks.NO_GROUP)) {
                    page.normalize();
                    Bookmarks.save();
                }
            }
            return true;
        }
        return false;
    }

    public static boolean hasFavorite(ItemStack stack) {
        return Favorites.recipeFor(RecipeData.idOf(stack)) != null;
    }

    private static RecipeShape favoriteShape(ItemStack stack) {
        String recipeId = Favorites.recipeFor(RecipeData.idOf(stack));
        return recipeId == null ? null : RecipeLookup.find(recipeId, stack);
    }

    /**
     * Shift + bookmark key over an item of JEI's list: toggles the item's favorite recipe in the default group, with its
     * count (NEI saveRecipeInBookmark with saveIngredients; recipeBookmarkWithCount is on by default, so Ctrl does not matter).
     */
    public static boolean onShiftListItem(ItemStack stack) {
        RecipeShape shape = favoriteShape(stack);
        if (shape == null) return false;
        Bookmarks.Page page = Bookmarks.page();
        RecipeData data = shape.toData(RecipeData.idOf(stack));
        if (!Bookmarks.removeRecipe(page, data.recipeId, Bookmarks.NO_GROUP)) {
            if (Bookmarks.addRecipe(page, data, 1, Bookmarks.NO_GROUP)) {
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
        Controller recipe = fresh(hoverLayout, layoutStamp);
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
        if (heart == null || heart.resultIds.size() <= 1) return;
        int dir = (int) Math.signum(event.getScrollDeltaY());
        if (dir == 0) return;
        heart.cycle(dir);
        event.setCanceled(true);
    }
}
