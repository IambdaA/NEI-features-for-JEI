package neiaddon;

import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Crafting tools (GregTech's hammers and files, and any other item that wears instead of being used up): one item per
 * stack, damageable, and what is left after a craft is the same item again. For these the chain tooltip shows durability
 * instead of a count, and autocrafting can put several sets around one tool.
 */
final class Tools {

    private Tools() {}

    static boolean isTool(ItemStack stack) {
        if (stack.isEmpty() || stack.getMaxStackSize() != 1 || !stack.isDamageableItem()) return false;
        ItemStack left = ContainerRules.leftAfterUse(stack);
        return !left.isEmpty() && left.getItem() == stack.getItem();
    }

    /** Durability one craft takes from the tool (GregTech: often more than 1); 0 when it does not wear. */
    static int durabilityPerCraft(ItemStack tool) {
        int per = 0;
        // a few tries: Unbreaking (and GregTech's own chance) can skip the damage of one craft
        for (int i = 0; i < 3; i++) {
            ItemStack left = ContainerRules.leftAfterUse(tool);
            if (left.isEmpty() || left.getItem() != tool.getItem()) return Math.max(1, tool.getMaxDamage() - tool.getDamageValue() + 1);
            per = Math.max(per, left.getDamageValue() - tool.getDamageValue());
        }
        return per;
    }

    /** Durability the tool has left. */
    static long durabilityLeft(ItemStack tool) {
        return Math.max(0, tool.getMaxDamage() - tool.getDamageValue());
    }

    /** Crafts the tool can still do (an estimate: it breaks once its damage goes past the maximum). */
    static long craftsLeft(ItemStack tool) {
        int per = durabilityPerCraft(tool);
        if (per <= 0) return Long.MAX_VALUE;
        return Math.max(1, durabilityLeft(tool) / per);
    }

    /** Durability of all the tools in the open screen's inventory slots whose item id is one of {@code ids}. */
    static long durabilityInInventory(Collection<String> ids) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        long sum = 0;
        for (Slot slot : mc.player.containerMenu.slots) {
            if (!slot.hasItem() || CraftSlots.isResult(slot) || !slot.mayPickup(mc.player)) continue;
            ItemStack stack = slot.getItem();
            if (!ids.contains(RecipeData.idOf(stack)) || !isTool(stack)) continue;
            long left = durabilityLeft(stack);
            sum = sum > Long.MAX_VALUE - left ? Long.MAX_VALUE : sum + left;
        }
        return sum;
    }
}
