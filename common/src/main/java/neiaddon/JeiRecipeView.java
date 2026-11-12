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

    private static java.lang.reflect.Field rectField;
    private static java.lang.reflect.Method[] rectGetters;
    private static boolean rectBroken;

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

    /**
     * The input slots with their areas on the screen. The slot's area is read from its own field (see {@link #slotRect});
     * when that cannot be read the list stops there, so no slot is highlighted.
     */
    @Override
    public List<Input> inputs() {
        List<Input> inputs = new ArrayList<>();
        Rect2i origin = layout.getRect();
        for (IRecipeSlotView view : layout.getRecipeSlotsView()
            .getSlotViews()) {
            if (view.getRole() != RecipeIngredientRole.INPUT || !(view instanceof IRecipeSlotDrawable slot)) continue;
            Rect2i area = slotRect(slot);
            if (area == null) break;
            Rect2i screen = new Rect2i(origin.getX() + area.getX(), origin.getY() + area.getY(), area.getWidth(), area.getHeight());
            inputs.add(new Input(screen, RecipeShape.altsOf(view)));
        }
        return inputs;
    }

    /**
     * The slot's own rectangle, relative to the recipe. JEI's {@code getAreaIncludingBackground} puts the slot's
     * background (an offset from the slot) at an offset from the recipe instead, so for most slots it is a box reaching
     * out to the top left corner of the recipe; the field it is built from is read directly. Null when that does not work.
     */
    private static Rect2i slotRect(IRecipeSlotDrawable slot) {
        if (rectBroken) return null;
        try {
            if (rectField == null) {
                rectField = slot.getClass()
                    .getDeclaredField("rect");
                rectField.setAccessible(true);
            }
            Object rect = rectField.get(slot);
            if (rectGetters == null) {
                rectGetters = new java.lang.reflect.Method[4];
                String[] names = { "getX", "getY", "getWidth", "getHeight" };
                for (int i = 0; i < 4; i++) {
                    rectGetters[i] = rect.getClass()
                        .getMethod(names[i]);
                    rectGetters[i].setAccessible(true);
                }
            }
            return new Rect2i((Integer) rectGetters[0].invoke(rect), (Integer) rectGetters[1].invoke(rect),
                (Integer) rectGetters[2].invoke(rect), (Integer) rectGetters[3].invoke(rect));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            rectBroken = true;
            NeiAddon.LOGGER.warn("Could not read the position of recipe slots: no highlight of present items", e);
            return null;
        }
    }

    @Override
    public boolean transfer(AbstractContainerMenu menu, Player player, boolean max) {
        return RecipeLookup.transfer(layout, menu, player, max);
    }
}
