package neiaddon;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;

/**
 * What the crafting tree needs from JEI: the icon and name of a recipe type (NEI's HandlerInfo), and the "+" button
 * that JEI's recipe screen shows while it was opened from the tree (NEI's TreeOverlayButton): it adds the recipe to the
 * tree's group, replacing the recipe of the node that was clicked.
 */
public final class TreeRecipes {

    private TreeRecipes() {}

    // ---------------------------------------------------------------- recipe types

    /** A recipe type as the tree shows it: its icon (null when it has none) and its name. */
    public record Info(IDrawable icon, String title) {}

    private static final Map<String, Info> INFOS = new HashMap<>();
    /** The EMI reload count the cached infos were made against (see {@link #info}). */
    private static int emiGeneration;

    public static void reset() {
        INFOS.clear();
    }

    /**
     * NEI's handler name -> JEI's recipe type uid (or EMI's category id). Never null; unknown types get no icon and the id
     * as name. A lookup made while EMI is still loading is not kept.
     */
    public static Info info(String handlerName) {
        int generation = EmiCompat.generation();
        if (generation != emiGeneration) {
            emiGeneration = generation;
            INFOS.clear();
        }
        Info info = INFOS.get(handlerName);
        if (info == null) {
            info = lookup(handlerName);
            // loading() can notice the end of a load and move the generation: then this answer is from before it
            if (!EmiCompat.loading() && EmiCompat.generation() == generation) INFOS.put(handlerName, info);
        }
        return info;
    }

    private static Info lookup(String handlerName) {
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null || handlerName == null) return new Info(null, String.valueOf(handlerName));
        try {
            Optional<RecipeType<?>> type = rt.getRecipeManager()
                .getRecipeType(ResourceLocation.parse(handlerName));
            if (type.isPresent()) {
                IRecipeCategory<?> category = category(rt.getRecipeManager(), type.get());
                return new Info(
                    category.getIcon(),
                    category.getTitle()
                        .getString());
            }
            // under EMI the recipe types JEI does not know are EMI's categories (GregTech machines, for one)
            if (EmiCompat.recipesAvailable()) {
                String name = EmiCompat.categoryName(handlerName);
                if (name != null) return new Info(EmiCompat.categoryIcon(handlerName), name);
            }
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not read recipe type {}", handlerName, e);
        }
        return new Info(null, handlerName);
    }

    private static <T> IRecipeCategory<T> category(IRecipeManager manager, RecipeType<T> type) {
        return manager.getRecipeCategory(type);
    }

    // ---------------------------------------------------------------- the "+" button

    /** The tree that is waiting for a recipe from JEI's recipe screen, or null. */
    private static CraftingTreeScreen waiting;

    /** True while the tree asks the recipe viewer for recipes (see {@link #lookup}). */
    private static boolean lookingUp;

    /**
     * The tree asks for the recipes or usages of one of its items ({@code show} opens the recipe screen) and waits for
     * the player to pick one with "+". EMI opens the player's inventory first when the open screen isn't a container
     * screen, then its recipe screen over it, both within {@code show}: that inventory doesn't end the wait.
     */
    static void lookup(CraftingTreeScreen tree, Runnable show) {
        waiting = tree;
        lookingUp = true;
        boolean shown = false;
        try {
            show.run();
            shown = true;
        } finally {
            lookingUp = false;
            if (!shown) waiting = null;
        }
        // the viewer stopped on another screen (the inventory, with no recipe screen over it): no "+" there
        Screen now = Minecraft.getInstance().screen;
        if (waiting != null && !(now instanceof CraftingTreeScreen) && !RecipeHearts.isRecipesScreen(now)) waiting = null;
    }

    static void stopWaiting() {
        waiting = null;
    }

    /** Anything but a recipe screen (or the tree itself) opening ends the wait, so "+" never shows up elsewhere. */
    @SubscribeEvent
    static void onOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (waiting == null) return;
        if (next instanceof CraftingTreeScreen) return;
        if (RecipeHearts.isRecipesScreen(next)) return;
        if (lookingUp && next instanceof AbstractContainerScreen<?>) return;
        waiting = null;
    }

    private static final String[] PLUS = { "..X..", "..X..", "XXXXX", "..X..", "..X.." };

    public static final IDrawable PLUS_ICON = new IDrawable() {

        @Override
        public int getWidth() {
            return 5;
        }

        @Override
        public int getHeight() {
            return 5;
        }

        @Override
        public void draw(GuiGraphics g, int xOffset, int yOffset) {
            for (int row = 0; row < PLUS.length; row++) {
                for (int col = 0; col < PLUS[row].length(); col++) {
                    if (PLUS[row].charAt(col) == 'X') g.fill(xOffset + col, yOffset + row, xOffset + col + 1, yOffset + row + 1, 0xFF3FBF3F);
                }
            }
        }
    };

    /** Is a crafting tree waiting for a recipe from JEI's recipe screen (the "+" button shows)? */
    public static boolean waiting() {
        return waiting != null;
    }


    /** The "+" of a recipe layout while a tree waits, else null (see {@link JeiRecipeButtons}, {@link RecipeButtonsFallback}). */
    static Controller create(IRecipeLayoutDrawable<?> layout) {
        if (waiting == null) return null;
        try {
            return create(RecipeShape.read(layout));
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not create the tree button for a recipe", e);
            return null;
        }
    }

    /** The "+" of a recipe shape while a tree waits, else null. For a recipe viewer other than JEI's. */
    public static Controller create(RecipeShape shape) {
        if (waiting == null) return null;
        try {
            RecipeData data = shape.toData(null);
            if (data.isEmpty()) return null;
            return new Controller(data);
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not create the tree button for a recipe", e);
            return null;
        }
    }

    /** The "+" of one recipe; knows nothing of JEI's button API (older JEI has none). */
    public static final class Controller {

        private final RecipeData data;

        Controller(RecipeData data) {
            this.data = data;
        }

        /** A click on the "+"; {@code simulate}: only say whether it would be used. */
        public boolean press(boolean simulate) {
            CraftingTreeScreen tree = waiting;
            if (tree == null) return false;
            if (!simulate) tree.addRecipe(data);
            return true;
        }

        /** The "+" button's tooltip: its name for the tree's overlay button. */
        public Component tooltip() {
            return Component.translatable("neiaddon.tree.overlay");
        }
    }
}
