package neiaddon;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import neiaddon.emi.EmiHistoryReturn;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;

/**
 * What the crafting tree needs from JEI: the icon and name of a recipe type (NEI's HandlerInfo), and the favorite button
 * of JEI's recipe screen while the tree waits for a recipe (NEI's GuiCraftingTree.addRecipe): the recipe is picked for the
 * tree, replacing the recipe of the node that was clicked.
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
                .getRecipeType(new ResourceLocation(handlerName));
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

    // ---------------------------------------------------------------- picking a recipe for the tree

    /** The tree that is waiting for a recipe from JEI's recipe screen, or null. */
    private static CraftingTreeScreen waiting;

    /** The item the waiting tree looked up (a recipes lookup), or null (a usages lookup, or none). */
    private static String waitingItem;

    /** True while the tree asks the recipe viewer for recipes (see {@link #lookup}). */
    private static boolean lookingUp;

    /**
     * The tree asks for the recipes or usages of one of its items ({@code show} opens the recipe screen) and waits for
     * the player to pick one with the favorite button. {@code item} is the item looked up for recipes (null for usages). EMI
     * opens the player's inventory first when the open screen isn't a container screen, then its recipe screen over it,
     * both within {@code show}: that inventory doesn't end the wait.
     */
    static void lookup(CraftingTreeScreen tree, String item, Runnable show) {
        waiting = tree;
        waitingItem = item;
        lookingUp = true;
        boolean shown = false;
        try {
            show.run();
            shown = true;
        } finally {
            lookingUp = false;
            if (!shown) endWait();
        }
        // the viewer stopped on another screen (the inventory, with no recipe screen over it): no pick there
        Screen now = Minecraft.getInstance().screen;
        if (waiting != null && !(now instanceof CraftingTreeScreen) && !RecipeHearts.isRecipesScreen(now)) endWait();
        // EMI's recipe screen goes back to the inventory it was put over: the tree takes that place in EMI's history
        if (waiting != null && EmiCompat.isRecipeScreen(now)) EmiHistoryReturn.putTreeBelow(tree, now);
    }

    static void stopWaiting() {
        endWait();
    }

    private static void endWait() {
        waiting = null;
        waitingItem = null;
    }

    /**
     * Anything but a recipe screen opening ends the wait, so the pick never shows up elsewhere; the tree opening (not from
     * a lookup) ends it too.
     */
    @SubscribeEvent
    static void onOpening(ScreenEvent.Opening event) {
        Screen next = event.getNewScreen();
        if (waiting == null) return;
        if (next instanceof CraftingTreeScreen) {
            if (!lookingUp) endWait();
            return;
        }
        if (RecipeHearts.isRecipesScreen(next)) return;
        if (lookingUp && next instanceof AbstractContainerScreen<?>) return;
        endWait();
    }

    /** Is a crafting tree waiting for a recipe from JEI's recipe screen (the favorite button picks for it)? */
    public static boolean waiting() {
        return waiting != null;
    }

    /**
     * The item the picked recipe is the favorite of: the item the tree looked up for recipes, else the item of the tree node
     * that the picked recipe replaces (the node that was clicked to open the recipe screen), else null.
     */
    public static String waitingItemId() {
        CraftingTreeScreen tree = waiting;
        if (tree == null) return null;
        return waitingItem != null ? waitingItem : tree.replacedItemId();
    }

    /**
     * Gives a recipe to the waiting tree: it goes into the tree's group in place of the replaced node's recipe, and the
     * tree is shown again. False when no tree waits.
     */
    public static boolean addToTree(RecipeData data) {
        CraftingTreeScreen tree = waiting;
        if (tree == null) return false;
        tree.addRecipe(data);
        return true;
    }
}
