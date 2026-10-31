package neiaddon;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ContainerOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The game side of the chain maths' container items (NEI: {@code item.hasContainerItem}, {@code getContainerItem},
 * {@code areStacksSameTypeCraftingWithNBT}). Stacks are {@link ItemStack}s of one item.
 *
 * <p>
 * Differences from NEI: the GregTech tool stats are not read, and a damaged copy of a tool counts as the same item as a
 * fresh one when comparing (like NEI's "same type crafting" for damageable items).
 */
final class ContainerRules implements ContainerOps {

    private final Map<String, ItemStack> byId = new HashMap<>();

    @Override
    public Object stackOf(ChainItem item) {
        if (item.payload instanceof Bookmarks.Entry e && e.key != null && !e.key.sample.isEmpty()) return e.key.sample.copy();
        return stackOf(item.item);
    }

    @Override
    public Object stackOf(String itemId) {
        ItemStack cached = byId.get(itemId);
        if (cached == null) {
            cached = FluidStacks.stackOf(itemId);
            byId.put(itemId, cached);
        }
        return cached.isEmpty() ? null : cached.copy();
    }

    @Override
    public String idOf(Object stack) {
        return RecipeData.idOf((ItemStack) stack);
    }

    @Override
    public boolean hasContainerItem(Object stack) {
        return remainder(stack) != null;
    }

    @Override
    public boolean sameForCrafting(Object a, Object b) {
        ItemStack x = (ItemStack) a;
        ItemStack y = (ItemStack) b;
        if (x.getItem() != y.getItem()) return false;
        if (x.isDamageableItem()) {
            x = x.copy();
            y = y.copy();
            x.setDamageValue(0);
            y.setDamageValue(0);
        }
        return ItemStack.isSameItemSameComponents(x, y);
    }

    @Override
    public boolean sameItem(Object a, Object b) {
        return ((ItemStack) a).getItem() == ((ItemStack) b).getItem();
    }

    @Override
    public Object remainder(Object stack) {
        try {
            return remainderOf((ItemStack) stack);
        } catch (RuntimeException | ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public Object copy(Object stack) {
        return ((ItemStack) stack).copy();
    }

    // ------------------------------------------------------------------ the one call that differs between versions

    private static Method remainderMethod;
    private static boolean remainderLooked;

    /**
     * What is left after using the stack once, or null. The method is looked up by name because the vanilla name and the
     * type it returns changed between versions (an ItemStack, or a template that makes one).
     */
    private static ItemStack remainderOf(ItemStack stack) throws ReflectiveOperationException {
        if (!remainderLooked) {
            remainderLooked = true;
            for (String name : new String[] { "getCraftingRemainder", "getCraftingRemainingItem" }) {
                try {
                    remainderMethod = ItemStack.class.getMethod(name);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (remainderMethod == null) NeiAddon.LOGGER.warn("No crafting remainder method found on ItemStack; container items are ignored");
        }
        if (remainderMethod == null) return null;
        Object result = remainderMethod.invoke(stack);
        if (result instanceof ItemStack s) return s.isEmpty() ? null : s;
        if (result != null) {
            // a template (item, count, components): its no-argument method that makes an ItemStack
            for (Method m : result.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == ItemStack.class) {
                    ItemStack s = (ItemStack) m.invoke(result);
                    return s == null || s.isEmpty() ? null : s;
                }
            }
        }
        return null;
    }
}
