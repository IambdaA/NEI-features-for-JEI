package neiaddon;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * What the crafting tree needs from JEI: the icon and name of a recipe type (NEI's HandlerInfo), and the "+" button
 * that JEI's recipe screen shows while it was opened from the tree (NEI's TreeOverlayButton): it adds the recipe to the
 * tree's group, replacing the recipe of the node that was clicked.
 */
@EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT)
public final class TreeRecipes {

    private static final String JEI_RECIPES_GUI = "mezz.jei.gui.recipes.RecipesGui";

    private TreeRecipes() {}

    // ---------------------------------------------------------------- recipe types

    /** A recipe type as the tree shows it: its icon (null when it has none) and its name. */
    public record Info(IDrawable icon, String title) {}

    private static final Map<String, Info> INFOS = new HashMap<>();

    public static void reset() {
        INFOS.clear();
    }

    /** NEI's handler name -> JEI's recipe type uid. Never null; unknown types get no icon and the id as name. */
    public static Info info(String handlerName) {
        return INFOS.computeIfAbsent(handlerName, TreeRecipes::lookup);
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

    static void waitFor(CraftingTreeScreen tree) {
        waiting = tree;
    }

    static void stopWaiting() {
        waiting = null;
    }

    /** Anything but JEI's recipe screen (or the tree itself) opening ends the wait, so "+" never shows up elsewhere. */
    @SubscribeEvent
    static void onOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (waiting == null) return;
        if (next instanceof CraftingTreeScreen) return;
        if (next != null && next.getClass()
            .getName()
            .equals(JEI_RECIPES_GUI)) return;
        waiting = null;
    }

    private static final String[] PLUS = { "..X..", "..X..", "XXXXX", "..X..", "..X.." };

    private static final IDrawable PLUS_ICON = new IDrawable() {

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

    public static final class Factory implements IRecipeButtonControllerFactory {

        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
            if (waiting == null) return null;
            try {
                RecipeShape shape = RecipeShape.read(layout);
                RecipeData data = shape.toData(null);
                if (data.isEmpty()) return null;
                return new Controller(data);
            } catch (RuntimeException e) {
                NeiAddon.LOGGER.warn("Could not create the tree button for a recipe", e);
                return null;
            }
        }
    }

    private static final class Controller implements IIconButtonController {

        private final RecipeData data;

        Controller(RecipeData data) {
            this.data = data;
        }

        @Override
        public void initState(IButtonState state) {
            state.setIcon(PLUS_ICON);
        }

        @Override
        public void updateState(IButtonState state) {
            state.setIcon(PLUS_ICON);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            CraftingTreeScreen tree = waiting;
            if (tree == null) return false;
            if (!input.isSimulate()) tree.addRecipe(data);
            return true;
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(Component.translatable("neiaddon.tree.overlay"));
        }
    }
}
