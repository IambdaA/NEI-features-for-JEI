package neiaddon;

import java.util.List;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** One recipe as a recipe screen shows it, for the heart, the tree's "+" and the keys over recipes (screen coordinates). */
public interface RecipeView {

    /** An input slot: its area on the screen and everything it accepts. */
    record Input(Rect2i area, List<RecipeShape.Alt> alts) {}

    /** The recipe's shape: its id, inputs and results (see {@link RecipeShape}). */
    RecipeShape shape();

    /** Is the mouse over the recipe on the screen right now? */
    boolean isMouseOver(int mouseX, int mouseY);

    /** Everything the slot at the point accepts, or an empty list; resultsOnly: only a result slot counts. */
    List<RecipeShape.Alt> altsAt(int mouseX, int mouseY, boolean resultsOnly);

    /** The input slots (not results, not catalysts). */
    List<Input> inputs();

    /** Moves the ingredients into the open crafting grid (max: as many crafts as fit); true when moved. */
    boolean transfer(AbstractContainerMenu menu, Player player, boolean max);
}
