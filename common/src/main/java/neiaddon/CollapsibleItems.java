package neiaddon;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.ItemTooltipEvent;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.TickEvent;
import neiaddon.platform.Platform;

/**
 * NEI's collapsible item groups: families of items (all wool colours, all spawn eggs, ...) show as one entry in JEI's item
 * list; Alt + click on it shows the whole group, Alt + click again folds it. The groups come from
 * {@code config/neiaddon/collapsibleitems.cfg} (written with defaults the first time): one group per line,
 * {@code Name = filter, filter, ...}, a filter being an item tag ({@code #minecraft:wool}), a mod ({@code @ae2}) or an item
 * id with {@code *} wildcards ({@code *:*_spawn_egg}). The first matching group of an item wins.
 *
 * <p>
 * JEI has no grouping of its own, so the members other than the first are taken out of JEI's list at runtime (and put back
 * when the group is opened, while JEI's search box has text, or when the setting is turned off). A small "+" in the slot
 * marks a closed group, "-" an open one. Off by default (Settings > Inventory > Collapsible Item Groups).
 */
public final class CollapsibleItems {

    private static final String DEFAULTS = """
        # NEI Features for JEI: collapsible item groups (Settings > Inventory > Collapsible Item Groups).
        # One group per line:  Name = filter, filter, ...
        # A filter is an item tag (#minecraft:wool), a mod (@ae2) or an item id with * wildcards (*:*_spawn_egg).
        # An item joins the first group that matches it. Alt + click on a group in JEI's list opens or closes it.
        Wool = #minecraft:wool
        Carpets = #minecraft:wool_carpets
        Beds = #minecraft:beds
        Banners = #minecraft:banners
        Candles = #minecraft:candles
        Terracotta = #minecraft:terracotta
        Glazed Terracotta = minecraft:*_glazed_terracotta
        Concrete = minecraft:*_concrete
        Concrete Powder = minecraft:*_concrete_powder
        Stained Glass = minecraft:*_stained_glass
        Stained Glass Panes = minecraft:*_stained_glass_pane
        Shulker Boxes = minecraft:*shulker_box
        Dyes = minecraft:*_dye
        Spawn Eggs = *:*_spawn_egg
        Potions = minecraft:potion
        Splash Potions = minecraft:splash_potion
        Lingering Potions = minecraft:lingering_potion
        Tipped Arrows = minecraft:tipped_arrow
        Enchanted Books = minecraft:enchanted_book
        """;

    /** A group of the file: its name and filters. */
    private record Rule(String name, List<String> filters) {}

    /** A group as found in JEI's list: two or more members, the first stays shown. */
    private static final class Found {

        final String name;
        final List<ItemStack> members = new ArrayList<>();
        boolean open, hidden;

        Found(String name) {
            this.name = name;
        }
    }

    private static final List<Found> found = new ArrayList<>();
    /** Every filter is item-level, so an item belongs to at most one group. */
    private static final Map<Item, Found> byItem = new IdentityHashMap<>();
    private static Object seenRuntime;
    private static boolean built, searching, applied, failed;

    private CollapsibleItems() {}

    private static boolean enabled() {
        return AddonConfig.COLLAPSIBLE_ITEMS.get();
    }

    // ---------------------------------------------------------------- the groups

    private static List<Rule> rules() {
        Path file = Platform.get()
            .configDir()
            .resolve("neiaddon")
            .resolve("collapsibleitems.cfg");
        List<Rule> rules = new ArrayList<>();
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8);
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                List<String> filters = new ArrayList<>();
                for (String f : line.substring(eq + 1)
                    .split(",")) {
                    if (!f.isBlank()) filters.add(f.strip());
                }
                if (!filters.isEmpty()) rules.add(new Rule(line.substring(0, eq)
                    .strip(), filters));
            }
        } catch (IOException e) {
            NeiAddon.LOGGER.warn("Could not read {}; no collapsible item groups", file, e);
        }
        return rules;
    }

    private static boolean matches(String filter, ItemStack stack, String id) {
        if (filter.startsWith("#")) {
            String tag = filter.substring(1);
            return stack.getTags().anyMatch(t -> t.location()
                .toString()
                .equals(tag));
        }
        if (filter.startsWith("@")) return id.startsWith(filter.substring(1) + ":");
        if (filter.indexOf('*') < 0) return id.equals(filter);
        String regex = ("\\Q" + filter + "\\E").replace("*", "\\E.*\\Q");
        return Pattern.matches(regex, id);
    }

    private static void build(IJeiRuntime rt) {
        built = true;
        found.clear();
        byItem.clear();
        List<Rule> rules = rules();
        if (rules.isEmpty()) return;
        Map<String, Found> byName = new LinkedHashMap<>();
        Collection<ItemStack> all = rt.getIngredientManager()
            .getAllIngredients(VanillaTypes.ITEM_STACK);
        for (ItemStack stack : all) {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString();
            for (Rule rule : rules) {
                boolean hit = false;
                for (String filter : rule.filters()) {
                    if (matches(filter, stack, id)) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    byName.computeIfAbsent(rule.name(), Found::new).members.add(stack.copy());
                    break;
                }
            }
        }
        for (Found f : byName.values()) {
            if (f.members.size() > 1) {
                found.add(f);
                for (ItemStack m : f.members) byItem.put(m.getItem(), f);
            }
        }
        NeiAddon.LOGGER.info("Collapsible item groups: {} groups from collapsibleitems.cfg", found.size());
    }

    /** Takes the members of closed groups out of JEI's list and puts the others back, in one call each. */
    private static void apply(IJeiRuntime rt, boolean showAll) {
        List<ItemStack> hide = new ArrayList<>();
        List<ItemStack> show = new ArrayList<>();
        for (Found f : found) {
            boolean shouldHide = !showAll && !searching && !f.open;
            if (shouldHide == f.hidden) continue;
            (shouldHide ? hide : show).addAll(f.members.subList(1, f.members.size()));
            f.hidden = shouldHide;
        }
        try {
            if (!hide.isEmpty()) rt.getIngredientManager()
                .removeIngredientsAtRuntime(VanillaTypes.ITEM_STACK, hide);
            if (!show.isEmpty()) rt.getIngredientManager()
                .addIngredientsAtRuntime(VanillaTypes.ITEM_STACK, show);
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            NeiAddon.LOGGER.warn("Could not change JEI's item list; collapsible item groups are off for this session", e);
        }
        applied = !showAll;
    }

    @SubscribeEvent
    static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        IJeiRuntime rt = Calculator.runtime;
        if (rt != seenRuntime) {
            // a new JEI runtime (world joined, JEI reloaded) has every item again
            seenRuntime = rt;
            found.clear();
            byItem.clear();
            built = applied = false;
        }
        if (rt == null || failed) return;
        if (!enabled()) {
            if (applied) apply(rt, true);
            built = false; // re-read the file when turned on again
            return;
        }
        if (!built) {
            if (applied) apply(rt, true);
            build(rt);
            applied = false;
        }
        boolean nowSearching = !rt.getIngredientFilter()
            .getFilterText()
            .isBlank();
        if (nowSearching != searching || !applied) {
            searching = nowSearching;
            apply(rt, false);
            applied = true;
        }
    }

    // ---------------------------------------------------------------- JEI's list: marker, click, tooltip

    private static Found groupOf(ItemStack stack) {
        return byItem.get(stack.getItem());
    }

    private static boolean isFirst(Found f, ItemStack stack) {
        ItemStack first = f.members.get(0);
        return first.getItem() == stack.getItem() && ItemStack.isSameItemSameTags(first, stack);
    }

    private static Optional<ItemStack> hoveredInList() {
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null) return Optional.empty();
        return rt.getIngredientListOverlay()
            .getIngredientUnderMouse()
            .flatMap(ITypedIngredient::getItemStack);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!enabled() || failed || found.isEmpty() || event.getButton() != 0 || !net.minecraft.client.gui.screens.Screen.hasAltDown()) return;
        Optional<ItemStack> stack = hoveredInList();
        if (stack.isEmpty()) return;
        Found f = groupOf(stack.get());
        if (f == null) return;
        f.open = !f.open;
        IJeiRuntime rt = Calculator.runtime;
        if (rt != null) apply(rt, false);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onTooltip(ItemTooltipEvent event) {
        if (!enabled() || found.isEmpty()) return;
        Optional<ItemStack> hovered = hoveredInList();
        if (hovered.isEmpty() || hovered.get()
            .getItem() != event.getItemStack()
                .getItem()) return;
        Found f = groupOf(event.getItemStack());
        if (f == null) return;
        event.getToolTip()
            .add(Component.literal("§8" + f.name + " (" + f.members.size() + "), Alt+click: " + (f.open ? "close" : "open")));
    }

    private static Field gridField, rendererField, slotsField, elementField, areaField;
    private static boolean markerFailed;

    /** "+" on a closed group's entry in JEI's list, "-" on an open group's first item. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        if (!enabled() || failed || markerFailed || found.isEmpty() || Calculator.runtime == null || !Calculator.runtime.getIngredientListOverlay()
            .isListDisplayed()) return;
        try {
            Object overlay = Calculator.runtime.getIngredientListOverlay();
            Object contents = JeiAreas.field(overlay.getClass(), "contents")
                .get(overlay);
            if (gridField == null) gridField = JeiAreas.field(contents.getClass(), "ingredientGrid");
            Object grid = gridField.get(contents);
            if (rendererField == null) rendererField = JeiAreas.field(grid.getClass(), "ingredientListRenderer");
            Object renderer = rendererField.get(grid);
            if (slotsField == null) slotsField = JeiAreas.field(renderer.getClass(), "slots");
            Font font = Minecraft.getInstance().font;
            GuiGraphics g = event.getGuiGraphics();
            Pose pose = Pose.of(g);
            pose.pushMatrix();
            pose.identity();
            pose.translateZ(300f);
            try {
                for (Object slot : (List<?>) slotsField.get(renderer)) {
                    if (elementField == null) {
                        elementField = JeiAreas.field(slot.getClass(), "element");
                        areaField = JeiAreas.field(slot.getClass(), "area");
                    }
                    Object element = elementField.get(slot);
                    if (element == null) continue;
                    Object typed = JeiAreas.invoke(element, "getTypedIngredient");
                    if (!(typed instanceof ITypedIngredient<?> ti)) continue;
                    Optional<ItemStack> stack = ti.getItemStack();
                    if (stack.isEmpty()) continue;
                    Found f = groupOf(stack.get());
                    if (f == null || !isFirst(f, stack.get())) continue;
                    Object area = areaField.get(slot);
                    int sx = ((Number) JeiAreas.invoke(area, "getX")).intValue();
                    int sy = ((Number) JeiAreas.invoke(area, "getY")).intValue();
                    int sw = ((Number) JeiAreas.invoke(area, "getWidth")).intValue();
                    String mark = f.hidden ? "+" : "-";
                    g.drawString(font, mark, sx + sw - font.width(mark) - 1, sy, f.hidden ? 0xFF55FF55 : 0xFFFFAA00, true);
                }
            } finally {
                pose.popMatrix();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            markerFailed = true;
            NeiAddon.LOGGER.warn("Could not find JEI's item list slots; collapsible groups get no marker", e);
        }
    }
}
