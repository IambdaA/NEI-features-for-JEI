package neiaddon;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.subtypes.UidContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

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
    /** Set while a tooltip is built to read an amount: that tooltip fires the tooltip event, which must not come back here. */
    private static boolean reading;
    /** Subtyped ids whose stacks carry their amount in millibuckets in the tooltip (NTM - Continuum's fluid items). */
    private static final Set<String> milli = new HashSet<>();
    /** A tooltip line that is only an amount: {@code 500mB}, {@code 1,500 mB}. */
    private static final Pattern MILLI_LINE = Pattern.compile("^\\s*([0-9][0-9,.]*)\\s*mB\\s*$");

    private StackIds() {}

    static void reset() {
        known.clear();
        milli.clear();
        scanned = false;
    }

    /** True once a subtyped item with a millibucket amount line has been seen (cheap guard for drawing code). */
    static boolean anyMilli() {
        return !milli.isEmpty();
    }

    static boolean reading() {
        return reading;
    }

    static boolean isMilli(String id) {
        return id != null && milli.contains(id);
    }

    /** The amount of a subtyped stack whose tooltip has a line of just "NNNmB" (0 when it has none); remembers the id. */
    static long milliAmount(ItemStack stack, String id) {
        if (id == null || id.indexOf(MARK) < 0) return 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;
        if (reading) return 0;
        reading = true;
        try {
            for (Component line : stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.Default.NORMAL)) {
                Matcher m = MILLI_LINE.matcher(line.getString());
                if (!m.matches()) continue;
                String digits = m.group(1)
                    .replace(",", "");
                int dot = digits.indexOf('.');
                if (dot >= 0) digits = digits.substring(0, dot);
                if (digits.isEmpty()) continue;
                long amount = Long.parseLong(digits);
                if (amount > 0) {
                    milli.add(id);
                    return amount;
                }
            }
        } catch (RuntimeException e) {
            // no amount
        } finally {
            reading = false;
        }
        return 0;
    }

    /** True when the stack's tooltip amount line (if any) can be shown in millibuckets: a known mB item. */
    static boolean replaceMilliLine(List<Component> lines, long amount) {
        for (int i = 0; i < lines.size(); i++) {
            if (!MILLI_LINE.matcher(lines.get(i)
                .getString())
                .matches()) continue;
            lines.set(i, Component.literal(amount + "mB")
                .withStyle(lines.get(i)
                    .getStyle()));
            return true;
        }
        return false;
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
