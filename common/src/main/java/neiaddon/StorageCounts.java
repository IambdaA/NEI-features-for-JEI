package neiaddon;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.ItemTooltipEvent;
import neiaddon.event.SubscribeEvent;

/**
 * How many of an item the storage network holds, while one of its terminals is open: the client knows nothing of a
 * network otherwise. Read from AE2's terminals (every version here), Refined Storage 1.x grids and Refined Storage 2 grids,
 * all by reflection, so a missing or changed mod just gives no counts. Shown as a "In storage" tooltip line and as a small
 * number on the bookmarks.
 */
public final class StorageCounts {

    private static final long REFRESH_NANOS = 250_000_000L;

    private static Map<String, Long> counts;
    private static Screen countedScreen;
    private static long countedAt;
    private static boolean aeFailed, rs1Failed, rs2Failed;

    private static Field aeRepo, aeEntries;
    private static Method aeWhat, aeAmount, aeId, aePerUnit, aeType;
    private static Method rsView, rsAll, rsIngredient, rsQuantity;
    private static Method rs2Repository, rs2Amount;
    private static Constructor<?> rs2Item;

    private StorageCounts() {}

    /**
     * The amount in the network of the open terminal (millibuckets for a fluid), 0 when the network has none, or -1 when no
     * terminal is open.
     */
    public static long count(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        Screen s = McCompat.screen();
        if (s == null) return -1;
        Long rs2 = rs2Count(s, stack);
        if (rs2 != null) return rs2;
        Map<String, Long> m = counts(s);
        return m == null ? -1 : m.getOrDefault(key(stack), 0L);
    }

    private static String key(ItemStack stack) {
        if (FluidStacks.isFluid(stack)) return FluidStacks.PREFIX + FluidStacks.fluidIdOf(stack);
        return BuiltInRegistries.ITEM.getKey(stack.getItem())
            .toString();
    }

    private static Class<?> superNamed(Class<?> c, String simpleName) {
        for (; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getSimpleName()
                .equals(simpleName)) return c;
        }
        return null;
    }

    /** Item and fluid ids -> amounts of the open AE2 terminal or Refined Storage 1 grid, refreshed a few times a second. */
    private static Map<String, Long> counts(Screen s) {
        long now = System.nanoTime();
        if (s == countedScreen && now - countedAt < REFRESH_NANOS) return counts;
        countedScreen = s;
        countedAt = now;
        counts = null;
        if (!aeFailed) {
            Class<?> terminal = superNamed(s.getClass(), "MEStorageScreen");
            if (terminal != null) counts = readAe2(s, terminal);
        }
        if (counts == null && !rs1Failed && s.getClass()
            .getName()
            .startsWith("com.refinedmods.")) {
            Class<?> grid = superNamed(s.getClass(), "GridScreen");
            if (grid != null) counts = readRs1(s);
        }
        return counts;
    }

    private static Map<String, Long> readAe2(Screen s, Class<?> terminal) {
        try {
            if (aeRepo == null) aeRepo = JeiAreas.field(terminal, "repo");
            Object repo = aeRepo.get(s);
            if (aeEntries == null) aeEntries = JeiAreas.field(repo.getClass(), "entries");
            Map<String, Long> out = new HashMap<>();
            for (Object entry : ((Map<?, ?>) aeEntries.get(repo)).values()) {
                if (aeWhat == null) {
                    aeWhat = entry.getClass()
                        .getMethod("getWhat");
                    aeAmount = entry.getClass()
                        .getMethod("getStoredAmount");
                }
                Object what = aeWhat.invoke(entry);
                long amount = (Long) aeAmount.invoke(entry);
                if (what == null || amount <= 0) continue;
                if (aeId == null) {
                    Class<?> key = Class.forName("appeng.api.stacks.AEKey");
                    aeId = key.getMethod("getId");
                    aeType = key.getMethod("getType");
                    aePerUnit = key.getMethod("getAmountPerUnit");
                }
                String id = aeId.invoke(what)
                    .toString();
                if (what.getClass()
                    .getSimpleName()
                    .contains("Fluid")) {
                    // AE2 counts fluids in the platform's units per bucket (1000 or 81000): this addon in millibuckets
                    int perUnit = Math.max(1, (Integer) aePerUnit.invoke(what));
                    out.merge(FluidStacks.PREFIX + id, amount * 1000 / perUnit, Long::sum);
                } else {
                    out.merge(id, amount, Long::sum);
                }
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            aeFailed = true;
            NeiAddon.LOGGER.warn("Could not read the AE2 terminal's items; no storage counts from AE2", e);
            return null;
        }
    }

    private static Map<String, Long> readRs1(Screen s) {
        try {
            if (rsView == null) rsView = s.getClass()
                .getMethod("getView");
            Object view = rsView.invoke(s);
            if (rsAll == null) rsAll = view.getClass()
                .getMethod("getAllStacks");
            Map<String, Long> out = new HashMap<>();
            for (Object stack : (Collection<?>) rsAll.invoke(view)) {
                if (rsIngredient == null) {
                    rsIngredient = stack.getClass()
                        .getMethod("getIngredient");
                    rsQuantity = stack.getClass()
                        .getMethod("getQuantity");
                }
                Object ingredient = rsIngredient.invoke(stack);
                long amount = ((Number) rsQuantity.invoke(stack)).longValue();
                if (ingredient instanceof ItemStack item) {
                    if (!item.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(item.getItem())
                        .toString(), amount, Long::sum);
                } else if (ingredient != null) {
                    // a FluidStack: its fluid's id, the amount already in millibuckets
                    Object fluid = ingredient.getClass()
                        .getMethod("getFluid")
                        .invoke(ingredient);
                    if (fluid instanceof net.minecraft.world.level.material.Fluid f) {
                        out.merge(FluidStacks.PREFIX + BuiltInRegistries.FLUID.getKey(f), amount, Long::sum);
                    }
                }
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            rs1Failed = true;
            NeiAddon.LOGGER.warn("Could not read the Refined Storage grid's items; no storage counts from it", e);
            return null;
        }
    }

    /** Refined Storage 2 asks its own repository; items only. Null when the screen is not one of its grids. */
    private static Long rs2Count(Screen s, ItemStack stack) {
        if (rs2Failed || !(s instanceof AbstractContainerScreen<?> screen) || !s.getClass()
            .getName()
            .startsWith("com.refinedmods.refinedstorage.common.")) return null;
        Object menu = screen.getMenu();
        try {
            if (rs2Repository == null) {
                rs2Repository = menu.getClass()
                    .getMethod("getRepository");
                Class<?> repository = Class.forName("com.refinedmods.refinedstorage.api.resource.repository.ResourceRepository");
                rs2Amount = repository.getMethod("getAmount", Class.forName("com.refinedmods.refinedstorage.api.resource.ResourceKey"));
                rs2Item = Class.forName("com.refinedmods.refinedstorage.common.support.resource.ItemResource")
                    .getConstructor(Item.class);
            }
            if (FluidStacks.isFluid(stack)) return 0L;
            Object repo = rs2Repository.invoke(menu);
            return (Long) rs2Amount.invoke(repo, rs2Item.newInstance(stack.getItem()));
        } catch (NoSuchMethodException e) {
            // a Refined Storage screen that is not a grid
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            rs2Failed = true;
            NeiAddon.LOGGER.warn("Could not read the Refined Storage grid's items; no storage counts from it", e);
            return null;
        }
    }

    static String format(ItemStack stack, long amount) {
        String n = NumberFormat.getIntegerInstance()
            .format(amount);
        return FluidStacks.isFluid(stack) ? n + " mB" : n;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onTooltip(ItemTooltipEvent event) {
        long n = count(event.getItemStack());
        if (n >= 0) event.getToolTip()
            .add(Component.literal("§bIn storage: " + format(event.getItemStack(), n)));
    }
}
