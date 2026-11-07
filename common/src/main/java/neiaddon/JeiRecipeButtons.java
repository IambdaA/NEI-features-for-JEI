package neiaddon;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory;
import mezz.jei.api.registration.IAdvancedRegistration;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

/**
 * The heart and the crafting tree's "+" as JEI's own recipe buttons. JEI's button API is newer than some JEI versions
 * modpacks ship (15.20 in ATM9, 19.25 in ATM10); this class is only loaded when JEI has it, see
 * {@link NeiAddonJeiPlugin#registerAdvanced}. Without it {@link RecipeButtonsFallback} draws the same buttons.
 */
final class JeiRecipeButtons {

    private JeiRecipeButtons() {}

    /** Does this JEI have the recipe button API? */
    static boolean available() {
        try {
            Class<?> factory = Class.forName("mezz.jei.api.recipe.advanced.IRecipeButtonControllerFactory");
            IAdvancedRegistration.class.getMethod("addRecipeButtonFactory", factory);
            Class.forName("mezz.jei.api.gui.buttons.IButtonState");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    static void register(IAdvancedRegistration registration) {
        registration.addRecipeButtonFactory(new Hearts());
        registration.addRecipeButtonFactory(new Trees());
    }

    private static final class Hearts implements IRecipeButtonControllerFactory {

        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
            RecipeHearts.Controller heart = RecipeHearts.create(layout, true);
            return heart == null ? null : new HeartButton(heart);
        }
    }

    private static final class HeartButton implements IIconButtonController {

        private final RecipeHearts.Controller heart;

        HeartButton(RecipeHearts.Controller heart) {
            this.heart = heart;
        }

        @Override
        public void initState(IButtonState state) {
            if (!heart.shown()) {
                state.setActive(false);
                state.setVisible(false);
            }
            state.setIcon(heart.icon());
        }

        @Override
        public void updateState(IButtonState state) {
            state.setIcon(heart.icon());
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            return heart.press(input.isSimulate());
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            for (Component line : heart.tooltipLines()) tooltip.add(line);
        }

        @Override
        public void drawExtras(GuiGraphics g, Rect2i buttonArea, int mouseX, int mouseY, float partialTicks) {
            heart.frame(buttonArea, mouseX, mouseY);
        }
    }

    private static final class Trees implements IRecipeButtonControllerFactory {

        @Override
        public <T> IIconButtonController createButtonController(IRecipeLayoutDrawable<T> layout) {
            TreeRecipes.Controller plus = TreeRecipes.create(layout);
            return plus == null ? null : new TreeButton(plus);
        }
    }

    private static final class TreeButton implements IIconButtonController {

        private final TreeRecipes.Controller plus;

        TreeButton(TreeRecipes.Controller plus) {
            this.plus = plus;
        }

        @Override
        public void initState(IButtonState state) {
            state.setIcon(TreeRecipes.PLUS_ICON);
        }

        @Override
        public void updateState(IButtonState state) {
            state.setIcon(TreeRecipes.PLUS_ICON);
        }

        @Override
        public boolean onPress(IJeiUserInput input) {
            return plus.press(input.isSimulate());
        }

        @Override
        public void getTooltips(ITooltipBuilder tooltip) {
            tooltip.add(plus.tooltip());
        }
    }
}
