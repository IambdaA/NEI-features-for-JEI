package neiaddon;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.api.helpers.IPlatformFluidHelper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;

/**
 * Over an item or fluid (bookmark panel, craftables, JEI's list, a recipe) the "type name in AE2 search" key writes its name
 * into the search box of the open Applied Energistics 2 screen. Only acts on screens of the {@code appeng} package.
 */
public final class Ae2Search {

    private static final Map<Class<?>, Optional<Field>> FIELDS = new HashMap<>();
    /** The typed character of the key press just handled still arrives as a character event: eat it. */
    private static long swallowUntil;

    private Ae2Search() {}

    static boolean isAe2(Screen screen) {
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName()
                .startsWith("appeng.")) return true;
        }
        return false;
    }

    /** The search box of an AE2 screen: its {@code searchField}, or else the first text box among its widgets. */
    private static EditBox searchBox(Screen screen) {
        Class<?> type = screen.getClass();
        Optional<Field> field = FIELDS.get(type);
        if (field == null) {
            Field found = null;
            for (Class<?> c = type; c != null && c != Object.class && found == null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("searchField");
                    f.setAccessible(true);
                    if (EditBox.class.isAssignableFrom(f.getType())) found = f;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // try the superclass
                }
            }
            field = Optional.ofNullable(found);
            FIELDS.put(type, field);
        }
        try {
            if (field.isPresent() && field.get()
                .get(screen) instanceof EditBox box) return box;
        } catch (ReflectiveOperationException | RuntimeException e) {
            NeiAddon.LOGGER.debug("Could not read the AE2 search box", e);
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof EditBox box) return box;
        }
        return null;
    }

    /** The name of the item or fluid under the mouse, or null. */
    private static String hoveredName(Screen screen) {
        ItemStack stack = CalcKeys.hoveredStack(screen, false);
        if (!stack.isEmpty()) return FluidStacks.name(stack)
            .getString();
        if (Calculator.runtime == null) return null;
        try {
            Optional<? extends ITypedIngredient<?>> listed = Calculator.runtime.getIngredientListOverlay()
                .getIngredientUnderMouse();
            if (listed.isPresent()) return displayName(listed.get());
            return fluidInRecipes(Calculator.runtime.getJeiHelpers()
                .getPlatformFluidHelper());
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.debug("Could not read the ingredient under the mouse", e);
            return null;
        }
    }

    private static <T> String displayName(ITypedIngredient<T> ingredient) {
        IIngredientHelper<T> helper = Calculator.runtime.getIngredientManager()
            .getIngredientHelper(ingredient.getType());
        return helper.getDisplayName(ingredient.getIngredient());
    }

    private static <T> String fluidInRecipes(IPlatformFluidHelper<T> helper) {
        Optional<T> fluid = Calculator.runtime.getRecipesGui()
            .getIngredientUnderMouse(helper.getFluidIngredientType());
        if (fluid.isEmpty()) return null;
        return Calculator.runtime.getIngredientManager()
            .getIngredientHelper(helper.getFluidIngredientType())
            .getDisplayName(fluid.get());
    }

    /** Before the other keys: over an item in an AE2 screen this key's default (T) would otherwise open the crafting tree. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!isAe2(screen) || Minecraft.getInstance()
            .hasControlDown()
            || Minecraft.getInstance()
                .hasAltDown()) return;
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(event.getKeyCode());
        if (!McCompat.matches(CalcKeys.SEARCH_NAME, key)) return;
        if (QuantityField.focused() || Calculator.runtime != null && Calculator.runtime.getIngredientListOverlay()
            .hasKeyboardFocus()) return;
        EditBox box = searchBox(screen);
        if (box == null) return;
        String name = hoveredName(screen);
        // nothing under the mouse: leave the key alone (typed into the box, or the crafting tree key)
        if (name == null || name.isBlank()) return;
        box.setValue(name);
        swallowUntil = System.nanoTime() + 200_000_000L;
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onChar(ScreenEvent.CharacterTyped.Pre event) {
        if (swallowUntil == 0) return;
        if (System.nanoTime() < swallowUntil) event.setCanceled(true);
        swallowUntil = 0;
    }
}
