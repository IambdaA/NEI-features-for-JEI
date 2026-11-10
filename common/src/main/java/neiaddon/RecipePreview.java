package neiaddon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.ItemTooltipEvent;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;

/**
 * NEI's recipe tooltip: the picture of an item's favorite recipe next to its tooltip. NEI makes it part of the tooltip
 * ({@code showRecipeTooltipInPanel}: items of the list, {@code showRecipeTooltipInGui}: items of a recipe screen). JEI
 * draws those tooltips itself, so the picture is a small framed box of its own beside the tooltip, on the side with room
 * (right of it, else left, below, above). Where the tooltip is comes from its lines, which are noted when the item
 * tooltip event runs.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class RecipePreview {

    private static List<Component> lastLines = List.of();
    private static ItemStack lastStack = ItemStack.EMPTY;

    private static Object cacheOwner;
    private static int cacheGeneration = -1;
    private static final Map<String, IRecipeLayoutDrawable<?>> pictures = new HashMap<>();
    private static final Set<String> noPicture = new HashSet<>();

    private RecipePreview() {}

    /** Last, so the lines are the ones that end up on screen. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onTooltip(ItemTooltipEvent event) {
        if (HotkeyTips.building || StackIds.reading()) return;
        lastLines = new ArrayList<>(event.getToolTip());
        lastStack = event.getItemStack();
    }

    private static void checkCaches() {
        if (cacheOwner != Calculator.runtime || cacheGeneration != RecipeLookup.generation()) {
            cacheOwner = Calculator.runtime;
            cacheGeneration = RecipeLookup.generation();
            pictures.clear();
            noPicture.clear();
        }
    }

    private static IRecipeLayoutDrawable<?> picture(String recipeId, ItemStack hint) {
        checkCaches();
        IRecipeLayoutDrawable<?> known = pictures.get(recipeId);
        if (known != null) return known;
        if (noPicture.contains(recipeId)) return null;
        try {
            RecipeLookup.Target<?> target = RecipeLookup.findTarget(recipeId, hint);
            IRecipeLayoutDrawable<?> layout = target == null ? null : target.layout();
            if (layout != null) {
                pictures.put(recipeId, layout);
                return layout;
            }
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Could not draw recipe {} next to the tooltip", recipeId, e);
        }
        noPicture.add(recipeId);
        return null;
    }

    /**
     * What the mouse is over: the item, the recipe to draw for it (null: the item's favorite) and, when the tooltip is
     * not the one the item tooltip event saw, its lines (to place the picture beside it).
     */
    private record Hover(ItemStack stack, String recipeId, List<FormattedCharSequence> ownLines) {}

    private static final Hover NOTHING = new Hover(ItemStack.EMPTY, null, null);

    /**
     * The item under the mouse. With the list option (NEI showRecipeTooltipInPanel): JEI's item list, the craftables
     * panel (its own recipe for the cell) and JEI's lookup history. With the recipe screen option: an item of a recipe.
     */
    private static Hover hovered(int mouseX, int mouseY) {
        if (Calculator.runtime == null) return NOTHING;
        try {
            if (AddonConfig.RECIPE_TIP_LIST.get()) {
                Optional<ItemStack> listed = HotkeyTips.hoveredInList();
                if (listed.isPresent()) return new Hover(listed.get(), null, null);
                ItemStack craftable = CraftablesPanel.hoveredStack();
                if (!craftable.isEmpty()) {
                    return new Hover(craftable, CraftablesPanel.hoveredRecipeId(), CraftablesPanel.hoveredTooltipLines());
                }
                Optional<ItemStack> past = JeiAreas.historyHovered(mouseX, mouseY);
                if (past.isPresent()) return new Hover(past.get(), null, null);
            }
            if (AddonConfig.RECIPE_TIP_GUI.get() && RecipeHearts.onRecipesScreen()) {
                ItemStack inRecipe = Calculator.runtime.getRecipesGui()
                    .getIngredientUnderMouse(VanillaTypes.ITEM_STACK)
                    .orElse(ItemStack.EMPTY);
                return inRecipe.isEmpty() ? NOTHING : new Hover(inRecipe, null, null);
            }
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.debug("Could not read the item under the mouse", e);
        }
        return NOTHING;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        if (!AddonConfig.RECIPE_TIP_LIST.get() && !AddonConfig.RECIPE_TIP_GUI.get()) return;
        Hover hover = hovered(event.getMouseX(), event.getMouseY());
        ItemStack stack = hover.stack();
        if (stack.isEmpty()) return;
        String recipeId = hover.recipeId() != null ? hover.recipeId() : Favorites.recipeFor(RecipeData.idOf(stack), stack);
        if (recipeId == null) return;
        IRecipeLayoutDrawable<?> picture = picture(recipeId, stack);
        if (picture == null) return;

        Font font = Minecraft.getInstance().font;
        int tipW = 0;
        int tipH;
        if (hover.ownLines() != null && !hover.ownLines()
            .isEmpty()) {
            // a tooltip the panel drew itself (the craftables panel)
            for (FormattedCharSequence line : hover.ownLines()) tipW = Math.max(tipW, font.width(line));
            tipH = hover.ownLines()
                .size() * 10 + (hover.ownLines()
                    .size() > 1 ? 2 : 0);
        } else {
            List<Component> lines = new ArrayList<>();
            if (lastStack.getItem() == stack.getItem() && !lastLines.isEmpty()) lines.addAll(lastLines);
            else lines.add(stack.getHoverName());
            // JEI adds the name of the mod below the item's own lines
            HotkeyTips.addModName(lines, stack);
            for (Component line : lines) tipW = Math.max(tipW, font.width(line));
            tipH = lines.size() * 10 + (lines.size() > 1 ? 2 : 0);
        }

        int mx = event.getMouseX();
        int my = event.getMouseY();
        int screenW = event.getScreen().width;
        int screenH = event.getScreen().height;
        // where the game puts the tooltip itself
        int tipX = mx + 12;
        int tipY = my - 12;
        boolean flipped = false;
        if (tipX + tipW > screenW) {
            tipX = Math.max(mx - 24 - tipW, 4);
            flipped = true;
        }
        if (tipY + tipH + 3 > screenH) tipY = screenH - tipH - 3;
        int left = tipX - 4;
        int right = tipX + tipW + 4;
        int top = tipY - 4;
        int bottom = tipY + tipH + 4;

        Rect2i r = picture.getRect();
        int bw = r.getWidth();
        int bh = r.getHeight() + 2;
        int gap = 6;
        int bx = Integer.MIN_VALUE;
        int by = Math.max(4, Math.min(tipY, screenH - bh - 4));
        boolean fitsRight = right + gap + bw + 4 <= screenW;
        boolean fitsLeft = left - gap - bw - 4 >= 0;
        if (!flipped && fitsRight) bx = right + gap;
        else if (flipped && fitsLeft) bx = left - gap - bw;
        else if (fitsRight) bx = right + gap;
        else if (fitsLeft) bx = left - gap - bw;
        else {
            // no room beside it: below the tooltip, else above it
            bx = Math.max(4, Math.min(tipX, screenW - bw - 4));
            if (bottom + gap + bh + 4 <= screenH) by = bottom + gap;
            else if (top - gap - bh - 4 >= 0) by = top - gap - bh;
            else return;
        }
        List<FormattedCharSequence> none = List.of();
        TreeDraw.boxAt(event.getGuiGraphics(), font, bx, by, none, bw, r.getHeight(), (g2, px, py) -> {
            picture.setPosition(px, py);
            // a layer of its own, above the tooltip box and the item icons under it
            g2.nextStratum();
            picture.drawRecipe(g2, -1000, -1000);
        });
    }
}
