package neiaddon;

import java.util.HashMap;
import java.util.Map;
import neiaddon.chain.ChainItem;
import neiaddon.chain.ContainerOps;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
            try {
                Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId))
                    .orElse(null);
                cached = item == null ? ItemStack.EMPTY : item.getDefaultInstance().copyWithCount(1);
            } catch (RuntimeException e) {
                cached = ItemStack.EMPTY;
            }
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
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public Object copy(Object stack) {
        return ((ItemStack) stack).copy();
    }

    // ------------------------------------------------------------------ the one call that differs between versions

    /** What is left after using the stack once (the loader's crafting remainder), or null. */
    private static ItemStack remainderOf(ItemStack stack) {
        ItemStack left = neiaddon.platform.Platform.get().craftingRemainder(stack);
        return left == null || left.isEmpty() ? null : left;
    }
}
