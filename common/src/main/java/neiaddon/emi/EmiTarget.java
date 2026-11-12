package neiaddon.emi;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import neiaddon.EmiCompat;
import neiaddon.NeiAddon;

/**
 * One EMI recipe as a transfer target (see {@link EmiCompat.RecipeHandle}). It shows the recipe in EMI, fills the open
 * crafting grid with EMI's own handler for it, and keeps the JEI recipe it maps to (if any) for the addon's drawings.
 * Failures are logged once per kind and answered with the safe default.
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
final class EmiTarget implements EmiCompat.RecipeHandle {

    private static final String NOT_IN_GRID = "it is not made in this crafting grid";
    private static final String FAILED = "EMI could not fill the grid";
    private static final String CARRIED = "an item is held on the cursor";

    private static boolean warnedShow;
    private static boolean warnedIcon;
    private static boolean warnedFill;
    /** EMI's filler could not be asked (reflection failed): no recipe is fillable from then on. */
    private static boolean fillBroken;
    private static Method firstValidHandler;

    /** What a fill needs: EMI's handler for the recipe and screen, the inventory, and the craft context. */
    private record Fill(EmiRecipeHandler handler, EmiPlayerInventory inventory, EmiCraftContext context) {}

    private final EmiRecipe recipe;
    private final IRecipeCategory<?> jeiCategory;
    private final Object jeiRecipe;

    EmiTarget(EmiRecipe recipe, IRecipeCategory<?> jeiCategory, Object jeiRecipe) {
        this.recipe = recipe;
        this.jeiCategory = jeiCategory;
        this.jeiRecipe = jeiRecipe;
    }

    @Override
    public void show() {
        try {
            EmiApi.displayRecipe(recipe);
        } catch (RuntimeException | LinkageError e) {
            if (!warnedShow) {
                warnedShow = true;
                NeiAddon.LOGGER.warn("Could not open a recipe in EMI", e);
            }
        }
    }

    @Override
    public IDrawable icon() {
        try {
            return EmiBridge.icon(recipe.getCategory());
        } catch (RuntimeException | LinkageError e) {
            if (!warnedIcon) {
                warnedIcon = true;
                NeiAddon.LOGGER.warn("Could not read the category of an EMI recipe; its icon is left out", e);
            }
            return null;
        }
    }

    @Override
    public boolean canFill(AbstractContainerMenu menu) {
        try {
            if (!menu.getCarried().isEmpty()) return false;
            Fill fill = prepare(menu, 1);
            return fill != null && fill.handler().canCraft(recipe, fill.context());
        } catch (RuntimeException | LinkageError e) {
            warnFill(e);
            return false;
        }
    }

    /**
     * Puts one craft into the grid, or as many as fit when {@code max}. EMI adds its batches to what the grid already
     * holds (and its own "fill max" amount overflows when the grid isn't empty), while the addon wants the grid
     * replaced, as JEI does: so the grid is emptied first, then filled with 1 or {@link #MAX_BATCHES} batches. Nothing
     * is filled when the grid could not be emptied (no room in the inventory); what was moved stays in the inventory.
     */
    @Override
    public boolean fill(AbstractContainerMenu menu, boolean max) {
        try {
            // EMI fills nothing while an item is on the cursor: leave the grid as it is then
            if (!menu.getCarried().isEmpty()) return false;
            Fill fill = prepare(menu, 1);
            if (fill == null || !fill.handler().canCraft(recipe, fill.context())) return false;
            if (!clearGrid(fill.handler(), menu)) return false;
            fill = prepare(menu, max ? MAX_BATCHES : 1);
            return fill != null && fill.handler().craft(recipe, fill.context());
        } catch (RuntimeException | LinkageError e) {
            warnFill(e);
            return false;
        }
    }

    /** A full stack in every grid slot: EMI fills no more than a stack holds, so this means "as many as fit". */
    private static final int MAX_BATCHES = 64;

    /**
     * Shift-clicks every crafting slot that holds something back into the inventory (the click is applied here at once and
     * sent to the server before EMI's fill). Only EMI's standard handlers name their grid slots; others are left alone.
     * False when a slot still holds something afterwards (the inventory had no room for it).
     */
    private static boolean clearGrid(EmiRecipeHandler handler, AbstractContainerMenu menu) {
        if (!(handler instanceof StandardRecipeHandler standard)) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || mc.player == null) return false;
        List<Slot> slots = standard.getCraftingSlots(menu);
        if (slots == null) return true;
        boolean empty = true;
        for (Slot slot : slots) {
            if (slot != null && slot.hasItem()) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, mc.player);
                if (slot.hasItem()) empty = false;
            }
        }
        return empty;
    }

    @Override
    public String whyNot(AbstractContainerMenu menu) {
        try {
            Fill fill = prepare(menu, 1);
            // prepare() is where a broken filler is found, so this is asked after it
            if (fill == null) return fillBroken ? FAILED : NOT_IN_GRID;
            if (!menu.getCarried().isEmpty()) return CARRIED;
            if (fill.handler().canCraft(recipe, fill.context())) return null;
            List<Boolean> available = fill.inventory().getCraftAvailability(recipe);
            List<EmiIngredient> inputs = recipe.getInputs();
            Map<String, Integer> missing = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(available.size(), inputs.size()); i++) {
                EmiIngredient input = inputs.get(i);
                if (!Boolean.FALSE.equals(available.get(i)) || input.isEmpty() || input.getEmiStacks().isEmpty()) continue;
                missing.merge(input.getEmiStacks().get(0).getName().getString(), 1, Integer::sum);
            }
            if (missing.isEmpty()) return "not enough ingredients";
            StringBuilder out = new StringBuilder("missing ");
            for (Map.Entry<String, Integer> e : missing.entrySet()) {
                if (out.length() > 8) out.append(", ");
                out.append(e.getKey());
                if (e.getValue() > 1) out.append(" x")
                    .append(e.getValue());
            }
            return out.toString();
        } catch (RuntimeException | LinkageError e) {
            warnFill(e);
            return FAILED;
        }
    }

    @Override
    public IRecipeCategory<?> jeiCategory() {
        return jeiCategory;
    }

    @Override
    public Object jeiRecipe() {
        return jeiRecipe;
    }

    /** What a fill needs for the open screen, or null: the screen is not this menu's, or EMI has no handler for the recipe. */
    private Fill prepare(AbstractContainerMenu menu, int amount) {
        if (fillBroken) return null;
        AbstractContainerScreen screen = EmiApi.getHandledScreen();
        if (screen == null || screen.getMenu() != menu) return null;
        EmiRecipeHandler handler = firstValidHandler(screen);
        if (handler == null || !handler.supportsRecipe(recipe)) return null;
        EmiPlayerInventory inventory = handler.getInventory(screen);
        EmiCraftContext context = new EmiCraftContext(screen, inventory, EmiCraftContext.Type.FILL_BUTTON,
            EmiCraftContext.Destination.NONE, amount);
        return new Fill(handler, inventory, context);
    }

    /** EMI's first handler for the recipe on this screen, which EMI's own fill button uses; found by reflection (cached). */
    private EmiRecipeHandler firstValidHandler(AbstractContainerScreen screen) {
        try {
            if (firstValidHandler == null) {
                firstValidHandler = Class.forName("dev.emi.emi.registry.EmiRecipeFiller")
                    .getMethod("getFirstValidHandler", EmiRecipe.class, AbstractContainerScreen.class);
            }
            return (EmiRecipeHandler) firstValidHandler.invoke(null, recipe, screen);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fillBroken = true;
            warnFill(e);
            return null;
        }
    }

    /** One warning for the first fill failure; later failures are answered quietly with the safe default. */
    private static void warnFill(Throwable e) {
        if (!warnedFill) {
            warnedFill = true;
            NeiAddon.LOGGER.warn("Could not fill the crafting grid from EMI's recipes; they are not fillable from the addon", e);
        }
    }
}
