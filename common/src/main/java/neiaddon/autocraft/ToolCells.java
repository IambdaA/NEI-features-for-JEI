package neiaddon.autocraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import neiaddon.CraftSlots;
import neiaddon.FluidStacks;
import neiaddon.NeiAddon;
import neiaddon.RecipeData;
import neiaddon.Tools;
import neiaddon.chain.ChainItem;
import neiaddon.chain.GridMath;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;

import static neiaddon.autocraft.Cleanup.*;
import static neiaddon.autocraft.ClickPath.*;
import static neiaddon.autocraft.Counting.*;
import static neiaddon.autocraft.Run.*;
import static neiaddon.autocraft.Visits.*;

/**
 * Tools in the crafting grid: finds the tools a recipe needs and clicks the missing ones into their cells.
 */
final class ToolCells {

    /**
     * After JEI's fill, put the recipe's tools into the grid when the fill left them out. The viewer caps a tool
     * recipe's fill at one set, and when the tool stays out of the grid it fills several sets without it (upstream
     * atm9-expert#87), which ends in "no result after the transfer". The tools of the visited recipe are derived the
     * way {@link Ae2Path#ae2MissingTool} derives one ({@code Tools.isTool} over its planned ingredients): all of them, not the
     * first. The ring recipe wants a hammer AND a file, and one tool already in the grid does not cover the other. Each
     * missing one goes into the cell the recipe's own layout gives it (see {@link #toolCell}: a shaped recipe's tool
     * cell is fixed, and dropping the tool into some other empty cell, an out-of-shape cell, which is where the first
     * empty slot of such a fill usually is, leaves the grid not matching), using the fill's own optimistic clicks (one
     * picks the stack up whole, one puts it in; a tool stack is one item). It runs in phase {@link Phase#ADD_TOOL},
     * after the fill's forced sync, so the grid it reads and clicks into is the server's, not this side's guess; the
     * clicks are synced again by the caller. The result is {@link ToolFill#NOT_NEEDED} when there is nothing to do (the
     * recipe wants no tool, or every tool it wants is already in the grid), {@link ToolFill#PLACED} when all were
     * placed, and a refusal (see {@link ToolFill}) when a wanted tool is in neither the grid nor any reachable slot: no
     * click may be made for it, and the player is told so in chat.
     */
    static ToolFill addMissingTool(Minecraft mc, String recipeId) {
        List<ChainItem> tools = toolIngredients(recipeId);
        if (tools.isEmpty()) return ToolFill.NOT_NEEDED;
        List<ChainItem> missing = new ArrayList<>();
        for (ChainItem tool : tools) {
            if (!gridHoldsTool(tool)) missing.add(tool);
        }
        if (missing.isEmpty()) return ToolFill.NOT_NEEDED;
        boolean clicked = false;
        // the grid indices already spoken for: the cells the fill placed into, and cells a tool went into a moment ago
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (int i = 0; i < gridSlots.size(); i++) {
            if (gridSlots.get(i).hasItem()) taken.add(i);
        }
        for (ChainItem tool : missing) {
            java.util.Set<String> kinds = toolKinds(tool);
            Slot from = null;
            for (Slot slot : menu.slots) {
                if (!slot.hasItem() || CraftSlots.isResult(slot) || !topSourceSlot(slot) || !Tools.isTool(slot.getItem())
                    || !kinds.contains(plainId(RecipeData.idOf(slot.getItem())))) continue;
                from = slot;
                break;
            }
            int cell = toolCell(mc, recipeId, tool, taken);
            Slot into = cell >= 0 ? gridSlots.get(cell) : null;
            if (into == null || into.hasItem()) {
                // No cell to be had - a shapeless recipe has no cells at
                // all (any empty cell is its cell), or the layout could not be read: the first empty cell
                into = null;
                for (Slot slot : gridSlots) {
                    if (slot.hasItem()) continue;
                    into = slot;
                    break;
                }
            }
            if (from == null || into == null) {
                String name = FluidStacks.name(FluidStacks.stackOf(tool.item)).getString();
                NeiAddon.LOGGER.info("Autocrafting: the grid fill of {} left the tool {} out, and neither the grid nor the inventory has one (grid:{})",
                    recipeId, name, describeGrid());
                if (mc.player != null) mc.player.sendSystemMessage(net.minecraft.network.chat.Component
                    .literal("§6[NEI Features] §ecould not craft " + recipeName(recipeId) + ": it needs a " + name + " and there is none in the inventory"));
                toolNamed.add(recipeId);
                return clicked ? ToolFill.REFUSED_AFTER_CLICKS : ToolFill.REFUSED;
            }
            clickNoWait(mc, from.index, 0);
            clickNoWait(mc, into.index, 0);
            clicked = true;
            taken.add(gridSlots.indexOf(into));
            NeiAddon.LOGGER.info("Autocrafting: the viewer's fill left the tool of {} out, adding it from the inventory", recipeId);
        }
        return ToolFill.PLACED;
    }

    /**
     * The tool ingredients of this recipe: {@code Tools.isTool} over its non-empty, non-fluid planned
     * ingredients. Read by the NEXT phase (does the recipe want a tool at all?) and by {@link #addMissingTool}.
     */
    static List<ChainItem> toolIngredients(String recipeId) {
        List<ChainItem> tools = new ArrayList<>();
        for (ChainItem ingredient : math.recipeIngredients) {
            if (!recipeId.equals(ingredient.recipeId) || ingredient.emptyFactor() || isFluid(ingredient)) continue;
            if (Tools.isTool(FluidStacks.stackOf(ingredient.item))) tools.add(ingredient);
        }
        return tools;
    }

    /** The plain ids standing for this tool ingredient: its own item and its tag options. */
    static java.util.Set<String> toolKinds(ChainItem tool) {
        java.util.Set<String> kinds = new java.util.HashSet<>();
        kinds.add(plainId(tool.item));
        for (String option : candidates(tool)) kinds.add(plainId(option));
        return kinds;
    }

    /** Does the grid already hold a stack of this tool ingredient's kind? */
    static boolean gridHoldsTool(ChainItem tool) {
        java.util.Set<String> kinds = toolKinds(tool);
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && Tools.isTool(slot.getItem()) && kinds.contains(plainId(RecipeData.idOf(slot.getItem())))) return true;
        }
        return false;
    }

    /**
     * The grid cell the visited recipe gives this tool. A shaped recipe's tool sits in a fixed cell, and the fill of
     * the viewer moves each ingredient of the recipe's own w x h grid to a slot of the 3x3 grid. The cell comes from
     * the vanilla recipe behind the chain's recipe id (see {@link Visits#vanillaCrafting}). Which placement is used: for a
     * shaped recipe in a 9-slot grid, the one the fill really used, found by {@link #fillOffset} from the grid's
     * contents (a smaller-than-3x3 recipe is not always at the JEI table's place, see
     * {@link GridMath#craftingIndex}, so the tool must go by the fill's placement); otherwise the table's placement. {@code taken} holds the grid indices
     * already spoken for. -1 when no cell can be derived: a shapeless recipe has no cells at all (any empty cell is its
     * cell), the vanilla recipe cannot be found, or its size is not that of the grid; the caller then falls back to the
     * first empty cell.
     */
    static int toolCell(Minecraft mc, String recipeId, ChainItem tool, java.util.Set<Integer> taken) {
        CraftingRecipe crafting = vanillaCrafting(mc, recipeId);
        // 26.x: only a shaped recipe has cells (the layout of a bridged recipe is not readable here)
        if (!(crafting instanceof ShapedRecipe shaped)) return -1;
        List<Optional<Ingredient>> cells = shaped.getIngredients();
        int w = shaped.getWidth();
        int h = shaped.getHeight();
        if (!GridMath.validSize(w, h, cells.size())) return -1;
        ItemStack want = FluidStacks.stackOf(tool.item);
        java.util.function.IntUnaryOperator used = i -> GridMath.craftingIndex(i, w, h);
        if (gridSlots.size() == 9) {
            int[] offset = fillOffset(recipeId, cells, w, h, want);
            if (offset != null) used = i -> GridMath.offsetIndex(i, w, offset[0], offset[1]);
        }
        return GridMath.toolCell(cellsEmpty(cells), cellsTakeTool(cells, want), used, gridSlots.size(),
                taken::contains);
    }

    /** Which cells of the recipe are empty. */
    private static boolean[] cellsEmpty(List<Optional<Ingredient>> cells) {
        boolean[] empty = new boolean[cells.size()];
        for (int i = 0; i < empty.length; i++) empty[i] = cells.get(i).map(Ingredient::isEmpty).orElse(true);
        return empty;
    }

    /** Which non-empty cells of the recipe accept the tool. */
    private static boolean[] cellsTakeTool(List<Optional<Ingredient>> cells, ItemStack want) {
        boolean[] takes = new boolean[cells.size()];
        for (int i = 0; i < takes.length; i++) {
            Ingredient cell = cells.get(i).orElse(null);
            takes[i] = cell != null && !cell.isEmpty() && cell.test(want);
        }
        return takes;
    }

    /** Which of the nine grid slots hold an item. */
    private static boolean[] gridHasItem() {
        boolean[] has = new boolean[9];
        for (int k = 0; k < 9; k++) has[k] = gridSlots.get(k).hasItem();
        return has;
    }

    /**
     * The offset {column, row} of the 3x3 grid where the fill really put a shaped
     * recipe of size w x h, or null to keep the JEI table's placement. Every offset that keeps the whole pattern
     * inside the grid is tried (see {@link GridMath#fitsPlacement}). The table's placement is used when it fits;
     * otherwise the one other offset that fits is the fill's; with none, or several, that fit without the table's, null.
     * Mirrored placements are not tried: the fill's mirror image is not a case seen in the logs.
     */
    static int[] fillOffset(String recipeId, List<Optional<Ingredient>> cells, int w, int h, ItemStack want) {
        int[] only = GridMath.fillOffset(w, h, cellsEmpty(cells), cellsTakeTool(cells, want), gridHasItem(),
            (cell, slot) -> cells.get(cell).map(c -> c.test(gridSlots.get(slot).getItem())).orElse(false));
        if (only == null) return null;
        NeiAddon.LOGGER.info("Autocrafting: the fill placed {} at column {}, row {}, not at the JEI table's place; the tool goes by that placement",
            recipeId, only[0], only[1]);
        return only;
    }

    /** Whether the grid holds a tool: its wear, unlike the materials, is not measurable
     *  by the click loop's counts, and it can break before the materials run out. */
    static boolean hasToolInGrid() {
        for (Slot slot : gridSlots) {
            if (slot.hasItem() && Tools.isTool(slot.getItem())) return true;
        }
        return false;
    }

    /**
     * The ADD_TOOL phase of {@link Runner#onTick}: clicks the recipe's missing tools into the grid.
     * Returns true where the original case body returned, so the tick ends.
     */
    static boolean addToolPhase(Minecraft mc) {
        // The screen the fill's sync fetched is the server's, so the tool goes in there
        ToolFill fill = addMissingTool(mc, current);
        if (fill == ToolFill.NOT_NEEDED) {
            // the fill carried the tool (or it is in the grid already): no second sync, the JEI path costs no round trip
            phase = Phase.WAIT_RESULT;
            waited = 0;
            return true;
        }
        if (fill == ToolFill.PLACED) {
            // the clicks are optimistic: the forced sync brings the server's grid back before the craft is read
            awaiting = false;
            waited = 0;
            syncUnverified = false;
            resumeAfterSync = Phase.WAIT_RESULT;
            phase = Phase.SYNC;
            return true;
        }
        // no tool to be had (the player was told in chat): the visit is deferred or given up
        if (!deferVisit(current, null)) abandonVisit(current, "no tool to be had");
        if (fill == ToolFill.REFUSED_AFTER_CLICKS) {
            // some tools were clicked in before the refusal: the forced sync tells the next visit what the server has
            awaiting = false;
            waited = 0;
            syncUnverified = false;
            resumeAfterSync = Phase.NEXT;
            phase = Phase.SYNC;
            return true;
        }
        // nothing was clicked, and the screen was just synced: no extra sync needed
        phase = Phase.NEXT;
        return true;
    }
}
