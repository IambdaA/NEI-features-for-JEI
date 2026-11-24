package neiaddon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mezz.jei.api.ingredients.ITypedIngredient;
import neiaddon.chain.ChainItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.SubscribeEvent;
import neiaddon.platform.Platform;
import neiaddon.event.ItemTooltipEvent;

/**
 * NEI's hotkey tooltips: an item shows "Hold ALT for hotkeys" and, while Alt is held, the list of keys that work on it.
 * Used by the bookmark panel (see {@link BookmarkPanel}) and, through the item tooltip event, for items of JEI's
 * ingredient list on the right.
 */
public final class HotkeyTips {

    /** Set while the bookmark panel builds its own tooltip, so the event below leaves it alone. */
    static boolean building;

    private HotkeyTips() {}

    public static boolean altDown() {
        return McCompat.alt();
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
        if (hotkeys.isEmpty() || !AddonConfig.SHOW_HOTKEYS.get() || !altDown() && !AddonConfig.HOTKEYS_HELP.get()) return List.of();
        return altDown() ? fitList(format(hotkeys)) : List.of(HOLD_ALT);
    }

    /** Lines of the tooltip besides the Alt list (the name, the mod name and a few more). */
    private static final int OTHER_TIP_LINES = 4;

    /**
     * Vanilla's tooltip keeps its bottom on the screen and loses its top lines when it is taller than the screen. When the
     * list plus the other lines would not fit, two entries share a line (the left column padded to its widest entry), and
     * three if two still do not fit. A list that fits is returned as it is.
     */
    static List<String> fitList(List<String> entries) {
        int screenH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int per = 1;
        while (per < 3 && (rows(entries.size(), per) + OTHER_TIP_LINES) * 10 + 16 > screenH) per++;
        return per == 1 ? entries : pack(entries, per);
    }

    private static int rows(int count, int per) {
        return (count + per - 1) / per;
    }

    /** Column-major: entry r, r + rows, r + 2 * rows... share line r; each column but the last is padded to its widest entry. */
    private static List<String> pack(List<String> entries, int per) {
        var font = Minecraft.getInstance().font;
        int n = entries.size();
        int rowCount = rows(n, per);
        int[] widest = new int[per];
        for (int i = 0; i < n; i++) widest[i / rowCount] = Math.max(widest[i / rowCount], font.width(entries.get(i)));
        List<String> out = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < per; c++) {
                int i = r + c * rowCount;
                if (i >= n) break;
                String cell = entries.get(i);
                line.append(cell);
                // the next entry of the line comes after this column's widest entry, padded with spaces
                if (c < per - 1 && r + (c + 1) * rowCount < n) {
                    for (int pad = widest[c] - font.width(cell); pad > 0; pad -= font.width(" ")) line.append(' ');
                    line.append("  ");
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    /** Keys that work on an item of JEI's ingredient list (NEI's ShortcutInputHandler.handleHotkeys, no bookmark slot). */
    public static Map<String, String> ingredientListKeys(ItemStack stack) {
        Map<String, String> k = new HashMap<>();
        // EMI: clicks on its stacks are EMI's (recipes, or EMI's own cheat mode), and on container screens EMI reads A
        // (its favorite key) before the addon does; on EMI's recipe screen the addon's keys come first, and with the
        // bookmark panel shown A over EMI's lists is turned into a bookmark (see EmiCompat.favoritesTick)
        boolean emi = EmiCompat.installed();
        boolean addKeyOurs = !emi || EmiCompat.isRecipeScreen(McCompat.screen())
            || BookmarkPanel.supports(McCompat.screen()) && !BookmarkPanel.isHidden();
        if (addKeyOurs) k.put(addKey(), "Add Bookmark");
        k.put(keys("CTRL", addKey()), "Add Bookmark with Count");
        if (RecipeHearts.hasFavorite(stack)) {
            k.put(keys("CTRL", "SHIFT", addKey()), "Add Bookmark with Recipe & Count");
            k.put(keys("SHIFT", addKey()), "Add Bookmark with Recipe");
            k.put(keys("SHIFT", CalcKeys.FAVORITE.getTranslatedKeyMessage()
                .getString()), "Save Recipe Tree");
            craftKeys(k);
            k.put(keys("CTRL", "SHIFT", chatKey()), "Send Recipe to Chat");
        } else if (!addKeyOurs) {
            // EMI's favorite key needs A alone, so Shift + A still reaches the addon; with no favorite recipe it makes a
            // plain bookmark
            k.put(keys("SHIFT", addKey()), "Add Bookmark");
        }
        recipeAndCopyKeys(k);
        if (CollapsibleItems.inGroup(stack)) k.put(keys("ALT", "LMB"), "Open/Close Item Group");
        if (Pull.available()) k.put(CalcKeys.PULL.getTranslatedKeyMessage()
            .getString(), "Pull Items");
        // under EMI the Ctrl give only works on EMI's own list, not on the inventory slots EMI also reports
        if (!emi || !JeiGive.emiSidebarStackUnderMouse()
            .isEmpty()) cheatClicks(k);
        if (k.get("LMB") != null && k.get("LMB").startsWith("Give")) {
            k.put(keys("SHIFT", "LMB"), "Recipe to make this item");
            k.put(keys("SHIFT", "RMB"), "Recipes that use this item");
        }
        return k;
    }

    /**
     * In cheat mode (JEI's or this addon's) a click on an item gives it instead of showing recipes (outside JEI's recipe
     * screen): left click the Stack Controls amount (a bookmark: its own amount), right click one; Shift + click on JEI's
     * list still shows recipes / uses. With EMI installed the give is Ctrl + click instead (EMI's own clicks show recipes), in
     * the cheat mode of {@link JeiGive#cheatingForClicks}.
     */
    private static void cheatClicks(Map<String, String> k) {
        if (EmiCompat.installed()) {
            if (!JeiGive.cheatingForClicks() || RecipeHearts.isRecipesScreen(McCompat.screen())) return;
            k.put(keys("CTRL", "LMB"), "Give Items");
            k.put(keys("CTRL", "RMB"), "Give One Item");
            return;
        }
        if (!JeiGive.cheating() || RecipeHearts.isRecipesScreen(McCompat.screen())) return;
        k.put("LMB", "Give Items");
        k.put("RMB", "Give One Item");
    }

    /** NEI's autocrafting keys; only offered while a crafting grid is open. */
    public static void craftKeys(Map<String, String> k) {
        if (!AutoCraft.available()) return;
        String craft = CalcKeys.CRAFT.getTranslatedKeyMessage()
            .getString();
        k.put(keys("CTRL", "SHIFT", craft), "Craft Items Again");
        k.put(keys("SHIFT", craft), "Craft Missing Items");
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
        String recipe = CalcKeys.showRecipeName();
        String uses = CalcKeys.showUsesName();
        if (recipe != null) k.put(recipe, "Recipe to make this item");
        if (uses != null) k.put(uses, "Recipes that use this item");
        k.put("LMB", "Recipe to make this item");
        k.put("RMB", "Recipes that use this item");
        k.put(keys("CTRL", "C"), "Copy Item Name");
        k.put(keys("CTRL", "D"), "Copy Item Tags");
        k.put(keys("CTRL", "X"), "Copy Item ID");
        k.put(keys("CTRL", chatKey()), "Send Item to Chat");
    }

    /** Keys that work on an item of the bookmark panel (NEI's BookmarkPanel.handleHotkeys + ShortcutInputHandler). */
    public static Map<String, String> panelKeys(Bookmarks.Page page, Bookmarks.Entry entry, boolean collapsed, boolean crafting, boolean editable,
        boolean bookmarkedRecipe) {
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
        if (bookmarkedRecipe) {
            // the bookmark's own recipe opens first (left click and JEI's show-recipes key)
            String recipe = CalcKeys.showRecipeName();
            if (recipe != null) k.put(recipe, "Show the Bookmarked Recipe");
            k.put("LMB", "Show the Bookmarked Recipe");
        }
        k.remove("SHIFT + LMB");
        cheatClicks(k);
        // Shift + click on a bookmark drags it, it never shows recipes
        k.put(keys("SHIFT", "LMB", "Drag"), "Move Position");
        return k;
    }

    /**
     * Adds the mod name line unless the tooltip already has one: another mod (a "mod name in tooltip" mod) may have put
     * the same name in the item's own tooltip lines, which these tooltips start from.
     */
    public static void addModName(List<Component> tip, ItemStack stack) {
        Component line = modName(stack);
        String name = ChatFormatting.stripFormatting(line.getString())
            .trim();
        for (Component existing : tip) {
            String text = ChatFormatting.stripFormatting(existing.getString());
            if (text != null && text.trim()
                .equalsIgnoreCase(name)) return;
        }
        tip.add(line);
    }

    /** The mod an item comes from, blue and italic like NEI/JEI show it. */
    public static Component modName(ItemStack stack) {
        String ns = FluidStacks.namespaceOf(stack);
        String name = ns;
        try {
            name = Platform.get().modDisplayName(ns).orElse(ns);
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
        if (building || StackIds.reading()) return;
        ItemStack stack = event.getItemStack();
        List<String> onResult = RecipeHearts.resultTooltipLines(stack);
        if (!onResult.isEmpty()) {
            // JEI adds the mod name after this event, so the end of the list is the line right above it
            for (String line : onResult) event.getToolTip()
                .add(Component.literal(line));
            return;
        }
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
