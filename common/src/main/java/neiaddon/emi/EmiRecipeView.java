package neiaddon.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.WidgetHolder;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import neiaddon.NeiAddon;
import neiaddon.RecipeShape;
import neiaddon.RecipeView;

/**
 * One recipe on EMI's recipe screen as the addon's {@link RecipeView} (see {@link EmiRecipeButtons}). The recipe's
 * widgets are in a {@link WidgetHolder} whose corner is moved to where it is drawn each frame ({@link #moveTo}), so the
 * slots' areas are turned into screen coordinates here. Every EMI call is caught: a failure answers the safe default and
 * logs one warning per kind.
 */
final class EmiRecipeView implements RecipeView {

    /** The slot flags of EMI's {@code SlotWidget} (protected fields), read once; null when they cannot be read. */
    private static boolean flagsTried;
    private static Field outputField;
    private static Field catalystField;

    private static boolean warnedSlots;
    private static boolean warnedAlts;
    private static boolean warnedGeometry;

    /** One slot of the recipe, as EMI drew it: its bounds relative to the recipe, its ingredient, its role and emptiness. */
    private record Slot(Bounds bounds, EmiIngredient stack, boolean output, boolean catalyst, boolean empty) {}

    private final EmiRecipe recipe;
    private final RecipeShape shape;
    private final WidgetHolder holder;
    /** Where the recipe's corner is on the screen now. */
    private int x, y;
    /** The slots, read on first use; null until then (an empty list when they cannot be read). */
    private List<Slot> slots;

    /** A view of one EMI recipe: its shape, and the holder its widgets were added to. */
    EmiRecipeView(EmiRecipe recipe, RecipeShape shape, WidgetHolder holder) {
        this.recipe = recipe;
        this.shape = shape;
        this.holder = holder;
    }

    /** Where the recipe's corner is on the screen now (called by the widgets every frame). */
    void moveTo(int x, int y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public RecipeShape shape() {
        return shape;
    }

    /** Is the mouse inside the recipe's area on the screen? */
    @Override
    public boolean isMouseOver(int mouseX, int mouseY) {
        try {
            return mouseX >= x && mouseX < x + holder.getWidth() && mouseY >= y && mouseY < y + holder.getHeight();
        } catch (RuntimeException | LinkageError e) {
            if (!warnedGeometry) {
                warnedGeometry = true;
                NeiAddon.LOGGER.warn("Could not read the size of an EMI recipe; its hover is off", e);
            }
            return false;
        }
    }

    /** Everything the slot under the point accepts; resultsOnly: only an output slot counts. */
    @Override
    public List<RecipeShape.Alt> altsAt(int mouseX, int mouseY, boolean resultsOnly) {
        Slot slot = slotAt(mouseX - x, mouseY - y);
        if (slot == null || (resultsOnly && !slot.output())) return List.of();
        return altsOf(slot.stack());
    }

    /** The input slots of the recipe (not outputs, not catalysts, not empty), on the screen. */
    @Override
    public List<Input> inputs() {
        List<Input> out = new ArrayList<>();
        for (Slot slot : slots()) {
            if (slot.output() || slot.catalyst() || slot.empty()) continue;
            List<RecipeShape.Alt> alts = altsOf(slot.stack());
            if (alts.isEmpty()) continue;
            Bounds b = slot.bounds();
            out.add(new Input(new Rect2i(x + b.x(), y + b.y(), b.width(), b.height()), alts));
        }
        return out;
    }

    /** Moves the recipe into the open grid through EMI's own handler for it (see {@link EmiTarget#fill}). */
    @Override
    public boolean transfer(AbstractContainerMenu menu, Player player, boolean max) {
        return new EmiTarget(recipe, null, null).fill(menu, max);
    }

    /** The slot whose bounds (relative to the recipe) hold the point, or null. */
    private Slot slotAt(int px, int py) {
        for (Slot slot : slots()) {
            if (slot.bounds().contains(px, py)) return slot;
        }
        return null;
    }

    /** The slots of the recipe, read on first use: the {@code SlotWidget}s of the holder's widget list. */
    private List<Slot> slots() {
        if (slots == null) slots = readSlots();
        return slots;
    }

    /**
     * EMI keeps the holder's widgets in the public field {@code widgets} of its group class. When that cannot be read the
     * recipe has no slots (one warning): the keys over its slots and the highlight of held items are off.
     */
    private List<Slot> readSlots() {
        List<Slot> out = new ArrayList<>();
        try {
            findFlags();
            Object widgets = holder.getClass()
                .getField("widgets")
                .get(holder);
            for (Object widget : (List<?>) widgets) {
                if (!(widget instanceof SlotWidget slot)) continue;
                boolean output;
                boolean catalyst;
                if (outputField == null || catalystField == null) {
                    output = slot.getRecipe() != null;
                    catalyst = false;
                } else {
                    output = outputField.getBoolean(slot);
                    catalyst = catalystField.getBoolean(slot);
                }
                EmiIngredient stack = slot.getStack();
                out.add(new Slot(slot.getBounds(), stack, output, catalyst, stack.isEmpty()));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            out.clear();
            if (!warnedSlots) {
                warnedSlots = true;
                NeiAddon.LOGGER.warn("Could not read the slots of an EMI recipe; the keys over EMI's recipe slots and the highlight of held items are off", e);
            }
        }
        return out;
    }

    /** Finds EMI's protected {@code output} and {@code catalyst} flags of a slot, once; if they are missing, null fields. */
    private static void findFlags() {
        if (flagsTried) return;
        flagsTried = true;
        try {
            outputField = SlotWidget.class.getDeclaredField("output");
            catalystField = SlotWidget.class.getDeclaredField("catalyst");
            outputField.setAccessible(true);
            catalystField.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            outputField = null;
            catalystField = null;
            NeiAddon.LOGGER.warn("Could not read the output and catalyst flags of EMI's recipe slots; a slot with a recipe counts as an output", e);
        }
    }

    /** The alternatives of an EMI ingredient; a failure answers none (one warning). */
    private List<RecipeShape.Alt> altsOf(EmiIngredient ingredient) {
        try {
            return EmiBridge.altsOf(ingredient);
        } catch (RuntimeException | LinkageError e) {
            if (!warnedAlts) {
                warnedAlts = true;
                NeiAddon.LOGGER.warn("Could not read the items of an EMI recipe slot", e);
            }
            return List.of();
        }
    }
}
