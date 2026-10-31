package neiaddon;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.subtypes.UidContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Ids of items that JEI keeps apart by subtype (a potion, an enchanted book, and in NTM - Continuum every fluid: one
 * {@code hbm:fluid_icon} item with data naming the fluid). The id is the registry id, then {@code ~} and JEI's own
 * recipe subtype data of the stack (URL-safe Base64), so two stacks are the same id exactly when JEI treats them as the same
 * ingredient, whatever else they carry. A stack is rebuilt from such an id from the stacks seen in recipes so far, or
 * else from JEI's ingredient list. An item JEI sees no subtype in keeps its plain registry id.
 */
final class StackIds {

    static final char MARK = '~';

    private static final Map<String, ItemStack> known = new HashMap<>();
    private static boolean scanned;

    private StackIds() {}

    static void reset() {
        known.clear();
        scanned = false;
    }

    private static boolean hasData(ItemStack stack) {
        return !stack.getComponentsPatch()
            .isEmpty();
    }

    /** The id of a subtyped stack, or null for a stack whose plain registry id says everything. */
    static String subtypeId(ItemStack stack) {
        if (stack.isEmpty() || !hasData(stack) || !Calculator.available()) return null;
        try {
            String plain = BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString();
            Object uid = Calculator.runtime.getIngredientManager()
                .getIngredientHelper(VanillaTypes.ITEM_STACK)
                .getUid(stack, UidContext.Recipe);
            // JEI's uid of a stack with subtype data is (item, data); a plain stack's uid is just the item
            if (!(uid instanceof List<?> parts) || parts.size() < 2) return null;
            String data = String.valueOf(parts.get(1));
            String id = plain + MARK
                + Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(data.getBytes(StandardCharsets.UTF_8));
            known.putIfAbsent(id, stack.copyWithCount(1));
            return id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The stack an item id stands for (plain or subtyped); {@link ItemStack#EMPTY} when the item is unknown. */
    static ItemStack parse(String id) {
        int at = id.indexOf(MARK);
        String name = at < 0 ? id : id.substring(0, at);
        try {
            var item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(name));
            if (item.isEmpty()) return ItemStack.EMPTY;
            if (at >= 0) {
                ItemStack seen = known.get(id);
                if (seen == null && !scanned && Calculator.available()) {
                    scanned = true;
                    for (ItemStack listed : Calculator.runtime.getIngredientManager()
                        .getAllIngredients(VanillaTypes.ITEM_STACK)) {
                        subtypeId(listed);
                    }
                    seen = known.get(id);
                }
                if (seen != null) return seen.copy();
            }
            return new ItemStack(item.get());
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;
        }
    }
}
