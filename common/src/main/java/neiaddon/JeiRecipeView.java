package neiaddon;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.inputs.RecipeSlotUnderMouse;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** A {@link RecipeView} over one of JEI's recipe layouts (the recipe screen's own layout). */
final class JeiRecipeView implements RecipeView {

    private final IRecipeLayoutDrawable<?> layout;
    private final RecipeShape shape;

    /** Reads the recipe's shape from the layout once; a RuntimeException from reading it propagates to the caller. */
    JeiRecipeView(IRecipeLayoutDrawable<?> layout) {
        this.layout = layout;
        this.shape = RecipeShape.read(layout);
    }

    /** The JEI layout this view wraps. */
    IRecipeLayoutDrawable<?> layout() {
        return layout;
    }

    @Override
    public RecipeShape shape() {
        return shape;
    }

    @Override
    public boolean isMouseOver(int mouseX, int mouseY) {
        return layout.isMouseOver(mouseX, mouseY);
    }

    @Override
    public List<RecipeShape.Alt> altsAt(int mouseX, int mouseY, boolean resultsOnly) {
        Optional<RecipeSlotUnderMouse> under = layout.getSlotUnderMouse(mouseX, mouseY);
        if (under.isEmpty()) return List.of();
        if (resultsOnly && under.get()
            .slot()
            .getRole() != RecipeIngredientRole.OUTPUT) {
            return List.of();
        }
        return RecipeShape.altsOf(
            under.get()
                .slot());
    }

    @Override
    public List<Input> inputs() {
        List<Input> inputs = new ArrayList<>();
        Rect2i origin = layout.getRect();
        for (IRecipeSlotView view : layout.getRecipeSlotsView()
            .getSlotViews()) {
            if (view.getRole() != RecipeIngredientRole.INPUT || !(view instanceof IRecipeSlotDrawable slot)) continue;
            // the slot's own rectangle (getAreaIncludingBackground puts the slot's background at the wrong place)
            Rect2i area = slot.getRect();
            Rect2i screen = new Rect2i(origin.getX() + area.getX(), origin.getY() + area.getY(), area.getWidth(), area.getHeight());
            inputs.add(new Input(screen, RecipeShape.altsOf(view)));
        }
        return inputs;
    }

    @Override
    public boolean transfer(AbstractContainerMenu menu, Player player, boolean max) {
        return RecipeLookup.transfer(layout, menu, player, max);
    }
}
