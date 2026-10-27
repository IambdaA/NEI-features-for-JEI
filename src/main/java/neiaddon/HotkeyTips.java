package neiaddon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.ingredients.ITypedIngredient;
import neiaddon.chain.ChainItem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * NEI's hotkey tooltips: an item shows "Hold ALT for hotkeys" and, while Alt is held, the list of keys that work on it.
 * Used by the bookmark panel (see {@link BookmarkPanel}) and, through the item tooltip event, for items of JEI's
 * ingredient list on the right.
 */
@EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT)
public final class HotkeyTips {

    /** Set while the bookmark panel builds its own tooltip, so the event below leaves it alone. */
    static boolean building;

    private HotkeyTips() {}

    public static boolean altDown() {
        return Minecraft.getInstance()
            .hasAltDown();
    }

    /** NEI's "showHotkeys": "Hold ALT for hotkeys", gray with ALT in gold. */
    public static Component holdAlt() {
        return Component.literal(HOLD_ALT);
    }

    public static final String HOLD_ALT = "§7Hold §6ALT§7 for hotkeys";

    /** Key names like NEI's KeyManager.getKeyName: modifiers first, joined with " + ". */
    public static String keys(String... parts) {
        return String.join(" + ", parts);
    }

    public static String chatKey() {
        return CalcKeys.CHAT_LINK.getTranslatedKeyMessage()
            .getString();
    }

    public static String addKey() {
        return CalcKeys.ADD.getTranslatedKeyMessage()
            .getString();
    }

    /**
     * NEI's GuiContainerManager.collectHotkeyTips: keys with the same message share a line ("R / LMB"), lines are sorted
     * by the length of their joined keys, then alphabetically. Keys gold, " / " and " - " dark gray, message gray.
     */
    public static List<String> format(Map<String, String> hotkeys) {
        Map<String, List<String>> byMessage = new HashMap<>();
        for (Map.Entry<String, String> e : hotkeys.entrySet()) {
            if (e.getKey() == null || e.getKey()
                .isEmpty()) continue;
            byMessage.computeIfAbsent(e.getValue(), m -> new ArrayList<>())
                .add(e.getKey());
        }
        for (List<String> keys : byMessage.values()) {
            keys.sort(
                (a, b) -> a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b));
        }
        List<Map.Entry<String, List<String>>> sorted = new ArrayList<>(byMessage.entrySet());
        sorted.sort((a, b) -> {
            String sa = String.join("/", a.getValue());
            String sb = String.join("/", b.getValue());
            return sa.length() != sb.length() ? Integer.compare(sa.length(), sb.length()) : sa.compareTo(sb);
        });
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : sorted) {
            out.add("§6" + String.join("§8 / §6", e.getValue()) + "§8 - §7" + e.getKey());
        }
        return out;
    }

    /** The lines to put right after the name: the hint, or the formatted list while Alt is held. */
    public static List<String> hotkeyLines(Map<String, String> hotkeys) {
        if (hotkeys.isEmpty() || !altDown() && !AddonConfig.HOTKEYS_HELP.get()) return List.of();
        return altDown() ? format(hotkeys) : List.of(HOLD_ALT);
    }

    /** Keys that work on an item of JEI's ingredient list (NEI's ShortcutInputHandler.handleHotkeys, no bookmark slot). */
    public static Map<String, String> ingredientListKeys(ItemStack stack) {
        Map<String, String> k = new HashMap<>();
        k.put(addKey(), "Add Bookmark");
        k.put(keys("CTRL", addKey()), "Add Bookmark with Count");
        if (RecipeHearts.hasFavorite(stack)) {
            k.put(keys("CTRL", "SHIFT", addKey()), "Add Bookmark with Recipe & Count");
            k.put(keys("SHIFT", addKey()), "Add Bookmark with Recipe");
            k.put(keys("SHIFT", CalcKeys.FAVORITE.getTranslatedKeyMessage()
                .getString()), "Save Recipe Tree");
            craftKeys(k);
            k.put(keys("CTRL", "SHIFT", chatKey()), "Send Recipe to Chat");
        }
        recipeAndCopyKeys(k);
        return k;
    }

    /** NEI's autocrafting keys; only offered while a crafting grid is open. */
    public static void craftKeys(Map<String, String> k) {
        if (!AutoCraft.available()) return;
        String craft = CalcKeys.CRAFT.getTranslatedKeyMessage()
            .getString();
        k.put(keys("CTRL", "SHIFT", craft), "Craft Missing Items");
        k.put(keys("SHIFT", craft), "Craft Items");
    }

    /** NEI's pull items keys; only offered while a chest is open. */
    public static void pullKeys(Map<String, String> k) {
        if (!Pull.available()) return;
        String pull = CalcKeys.PULL.getTranslatedKeyMessage()
            .getString();
        k.put(pull, "Pull all Items");
        k.put(keys("SHIFT", pull), "Pull missing Items");
    }

    /** The keys every item has: recipes (JEI does these) and the copy keys. */
    public static void recipeAndCopyKeys(Map<String, String> k) {
        k.put("R", "Recipe to make this item");
        k.put("U", "Recipes that use this item");
        k.put("LMB", "Recipe to make this item");
        k.put("RMB", "Recipes that use this item");
        k.put(keys("CTRL", "C"), "Copy Item Name");
        k.put(keys("CTRL", "D"), "Copy Item Tags");
        k.put(keys("CTRL", "X"), "Copy Item ID");
        k.put(keys("CTRL", chatKey()), "Send Item to Chat");
    }

    /** Keys that work on an item of the bookmark panel (NEI's BookmarkPanel.handleHotkeys + ShortcutInputHandler). */
    public static Map<String, String> panelKeys(Bookmarks.Page page, Bookmarks.Entry entry, boolean collapsed, boolean crafting, boolean editable) {
        Map<String, String> k = new HashMap<>();
        String add = addKey();
        if (!entry.isRecipe()) {
            k.put(add, "Remove Item");
        } else {
            boolean onlyResult = entry.type == ChainItem.Type.RESULT && page.entries.stream()
                .noneMatch(m -> m != entry && m.type == ChainItem.Type.RESULT && m.sameRecipe(entry.recipeId, entry.groupId));
            k.put(add, onlyResult ? "Remove Recipe" : "Remove Item");
            if (!onlyResult) k.put(keys("SHIFT", add), "Remove Recipe");
        }
        k.put(keys("CTRL", "Scroll"), "Change Quantity");
        k.put(keys("CTRL", "ALT", "Scroll"), "Change Quantity with Multiplier");
        k.put(keys("SHIFT", "LMB", "Drag"), "Move Position");
        // NEI: a collapsed group expands again, a slot of a crafting chain collapses/expands its recipe
        if (collapsed || crafting) k.put(keys("ALT", "LMB"), "Toggle Collapse/Expand");
        pullKeys(k);
        if (editable && Bookmarks.hasOptions(entry)) k.put(keys("SHIFT", "Scroll"), "Change Item");
        if (entry.isRecipe() && entry.type != ChainItem.Type.INGREDIENT) craftKeys(k);
        if (entry.type == ChainItem.Type.RESULT) k.put(keys("CTRL", "SHIFT", chatKey()), "Send Recipe to Chat");
        recipeAndCopyKeys(k);
        return k;
    }

    /** The mod an item comes from, blue and italic like NEI/JEI show it. */
    public static Component modName(ItemStack stack) {
        String ns = BuiltInRegistries.ITEM.getKey(stack.getItem())
            .getNamespace();
        String name = ns;
        try {
            name = ModList.get()
                .getModContainerById(ns)
                .map(
                    c -> c.getModInfo()
                        .getDisplayName())
                .orElse(ns);
        } catch (RuntimeException e) {
            // keep the namespace
        }
        return Component.literal("§9§o" + name);
    }

    public static Optional<ItemStack> hoveredInList() {
        if (Calculator.runtime == null) return Optional.empty();
        try {
            return Calculator.runtime.getIngredientListOverlay()
                .getIngredientUnderMouse()
                .flatMap(ITypedIngredient::getItemStack);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        if (building) return;
        ItemStack stack = event.getItemStack();
        Optional<ItemStack> hovered = hoveredInList();
        if (hovered.isEmpty() || hovered.get()
            .getItem() != stack.getItem()) return;
        List<Component> tip = event.getToolTip();
        if (tip.isEmpty()) return;
        // right after the name: the hint, or the whole list while Alt is held
        int at = 1;
        for (String line : hotkeyLines(ingredientListKeys(stack))) tip.add(at++, Component.literal(line));
    }
}
