package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;

/**
 * Finds the crafting grid and the result slot of a screen, also at other mods' crafting tables (Tinkers' Crafting Station,
 * Sophisticated Storage's crafting upgrade, ...). The result slot is the game's own kind, or one that looks like it (its
 * class is called a result slot, or it sits on a result container) on a screen JEI can move crafting recipes into. The
 * grid is the slots on a crafting container, else the recipe slots the screen itself, one of its parts, or JEI's
 * transfer info for it names.
 */
final class CraftSlots {

    private static final Map<Class<?>, Boolean> jeiCrafting = new WeakHashMap<>();
    private static Object cacheOwner;

    private CraftSlots() {}

    /** A result slot (the game's or one that looks like it); not one of the player's inventory slots. */
    static boolean isResult(Slot slot) {
        if (slot instanceof ResultSlot) return true;
        if (slot.container instanceof Inventory) return false;
        return slot.container instanceof ResultContainer || slot.getClass()
            .getName()
            .contains("ResultSlot");
    }

    /** The crafting result slot of the menu, or null when it is not a crafting screen. */
    static Slot result(AbstractContainerMenu menu) {
        for (Slot slot : menu.slots) {
            if (slot instanceof ResultSlot) return slot;
        }
        if (!jeiCanCraft(menu)) return null;
        for (Slot slot : menu.slots) {
            if (isResult(slot)) return slot;
        }
        return null;
    }

    /** Can JEI move crafting recipes into this menu (a transfer handler for crafting is registered for it)? */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    static boolean jeiCanCraft(AbstractContainerMenu menu) {
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null) return false;
        if (cacheOwner != rt) {
            cacheOwner = rt;
            jeiCrafting.clear();
        }
        return jeiCrafting.computeIfAbsent(menu.getClass(), k -> {
            try {
                IRecipeCategory category = rt.getRecipeManager()
                    .getRecipeCategory(RecipeTypes.CRAFTING);
                return rt.getRecipeTransferManager()
                    .getRecipeTransferHandler(menu, category)
                    .isPresent();
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        });
    }

    /** The menu's crafting grid, or none when it cannot be found (autocrafting then crafts one set at a time). */
    static List<Slot> grid(AbstractContainerMenu menu) {
        List<Slot> grid = new ArrayList<>();
        for (Slot slot : menu.slots) {
            if (slot.container instanceof CraftingContainer) grid.add(slot);
        }
        if (!grid.isEmpty()) return grid;
        // the menu itself, or one of its parts (Sophisticated Storage's upgrade containers), may name its recipe slots
        List<Slot> named = recipeSlots(menu, menu);
        if (named != null) return named;
        Object parts = call(menu, "getUpgradeContainers");
        if (parts instanceof Map<?, ?> map) {
            for (Object part : map.values()) {
                named = recipeSlots(menu, part);
                if (named != null) return named;
            }
        }
        named = jeiRecipeSlots(menu);
        return named != null ? named : List.of();
    }

    private static Object call(Object o, String name) {
        if (o == null) return null;
        try {
            Method m = o.getClass()
                .getMethod(name);
            m.setAccessible(true);
            return m.invoke(o);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** {@code o.getRecipeSlots()} when it is a list of the menu's slots, else null. */
    private static List<Slot> recipeSlots(AbstractContainerMenu menu, Object o) {
        return checked(menu, call(o, "getRecipeSlots"));
    }

    private static List<Slot> checked(AbstractContainerMenu menu, Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) return null;
        List<Slot> slots = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Slot slot) || !menu.slots.contains(slot) || slot.container instanceof Inventory) return null;
            slots.add(slot);
        }
        return slots;
    }

    /** The recipe slots of JEI's transfer info for the menu (a basic transfer handler keeps one), or null. */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static List<Slot> jeiRecipeSlots(AbstractContainerMenu menu) {
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null) return null;
        try {
            IRecipeCategory category = rt.getRecipeManager()
                .getRecipeCategory(RecipeTypes.CRAFTING);
            Object handler = rt.getRecipeTransferManager()
                .getRecipeTransferHandler(menu, category)
                .orElse(null);
            if (handler == null) return null;
            Object recipe = rt.getRecipeManager()
                .createRecipeLookup(RecipeTypes.CRAFTING)
                .get()
                .findFirst()
                .orElse(null);
            if (recipe == null) return null;
            for (Class<?> c = handler.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!mezz.jei.api.recipe.transfer.IRecipeTransferInfo.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object info = f.get(handler);
                    if (info instanceof mezz.jei.api.recipe.transfer.IRecipeTransferInfo transferInfo) {
                        List<Slot> slots = checked(menu, transferInfo.getRecipeSlots(menu, recipe));
                        if (slots != null) return slots;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.debug("No recipe slots from JEI's transfer info for {}", menu.getClass().getName(), e);
        }
        return null;
    }
}
