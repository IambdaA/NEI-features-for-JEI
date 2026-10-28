package neiaddon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;

/**
 * NEI's craftables panel (ItemCraftablesPanel): a strip on the right, just above the quantity field, listing what the
 * open container can craft from the player's inventory right now. A recipe counts when JEI's own transfer handler for
 * this container accepts it with the current inventory (the same check as JEI's "+" button), so the panel shows exactly
 * what the "+" button could fill in. Favorite recipes win over other recipes of the same item and come first.
 *
 * <p>
 * Left click: recipes of the item, right click: its uses, Shift + craft key: autocraft one run (Ctrl keeps what the
 * inventory already holds, like NEI), wheel: scroll rows. The scan runs a few milliseconds per frame, at most every
 * 600 ms, and only when the inventory changed (or every 5 s), because creating JEI recipe layouts is slow.
 */
public final class CraftablesPanel {

    private static final int CELL = 18;
    private static final int PAD = 2;
    private static final int GAP_ABOVE_BAR = 4;
    private static final long MIN_INTERVAL_MS = 600;
    private static final long REFRESH_MS = 5000;
    private static final long BUDGET_NANOS = 3_000_000L;
    private static final int MAX_ITEMS_SCANNED = 64;
    private static final int MAX_CACHE = 5000;
    private static final int BACKGROUND = 0xEE555555;
    private static final int HIGHLIGHT = 0x80FFFFFF;

    /** A recipe JEI knows, with what it makes; kept until JEI reloads. */
    private static final class Cached {

        final RecipeLookup.Target<?> target;
        final ItemStack out;
        final String id;
        final String outId;

        Cached(RecipeLookup.Target<?> target, ItemStack out, String id) {
            this.target = target;
            this.out = out;
            this.id = id;
            this.outId = RecipeData.idOf(out);
        }

        boolean favorite() {
            return id.equals(Favorites.recipeFor(outId));
        }
    }

    private static final Map<Object, Cached> cache = new IdentityHashMap<>();
    private static final Cached NONE = new Cached(null, ItemStack.EMPTY, "");

    private static final class Job {

        final AbstractContainerMenu menu;
        final Player player;
        final Deque<Runnable> queue = new ArrayDeque<>();
        final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Cached> craftable = new ArrayList<>();

        Job(AbstractContainerMenu menu, Player player) {
            this.menu = menu;
            this.player = player;
        }
    }

    private static Job job;
    private static List<Cached> results = List.of();
    private static AbstractContainerMenu resultMenu;
    private static long lastStart;
    private static int lastSignature = Integer.MIN_VALUE;
    private static int scroll;
    private static int hoverIndex = -1;

    private CraftablesPanel() {}

    public static void reset() {
        cache.clear();
        job = null;
        results = List.of();
        lastSignature = Integer.MIN_VALUE;
    }

    // ---------------------------------------------------------------- scanning

    private static int signature(AbstractContainerMenu menu) {
        int h = 1;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) continue;
            ItemStack s = slot.getItem();
            h = h * 31 + (s.isEmpty() ? 0 : RecipeData.idOf(s)
                .hashCode() * 7 + s.getCount());
        }
        return h;
    }

    private static void tick(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null || mc.player == null) return;
        AbstractContainerMenu menu = screen.getMenu();
        if (menu != resultMenu) {
            resultMenu = menu;
            results = List.of();
            job = null;
            lastSignature = Integer.MIN_VALUE;
            scroll = 0;
        }
        long now = System.currentTimeMillis();
        if (job == null) {
            int sig = signature(menu);
            boolean changed = sig != lastSignature;
            if (now - lastStart >= MIN_INTERVAL_MS && (changed || now - lastStart >= REFRESH_MS)) {
                lastStart = now;
                lastSignature = sig;
                job = start(rt, menu, mc.player);
            }
            return;
        }
        long end = System.nanoTime() + BUDGET_NANOS;
        try {
            while (!job.queue.isEmpty() && System.nanoTime() < end) job.queue.poll()
                .run();
        } catch (RuntimeException e) {
            NeiAddon.LOGGER.warn("Craftables scan failed", e);
            job = null;
            return;
        }
        if (job.queue.isEmpty()) {
            results = publish(job.craftable);
            job = null;
        }
    }

    private static Job start(IJeiRuntime rt, AbstractContainerMenu menu, Player player) {
        Job j = new Job(menu, player);
        Set<String> ids = new java.util.HashSet<>();
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory)) continue;
            ItemStack s = slot.getItem();
            if (s.isEmpty() || ids.size() >= MAX_ITEMS_SCANNED || !ids.add(RecipeData.idOf(s))) continue;
            ItemStack sample = McCompat.copyWithCount(s, 1);
            j.queue.add(() -> gather(rt, j, sample));
        }
        return j;
    }

    private static void gather(IJeiRuntime rt, Job j, ItemStack sample) {
        IRecipeManager manager = rt.getRecipeManager();
        IFocus<ItemStack> focus = rt.getJeiHelpers()
            .getFocusFactory()
            .createFocus(RecipeIngredientRole.INPUT, VanillaTypes.ITEM_STACK, sample);
        List<IFocus<?>> focuses = List.of(focus);
        manager.createRecipeCategoryLookup()
            .limitFocus(focuses)
            .get()
            .forEach(category -> gatherCategory(rt, j, category, focuses));
    }

    private static <R> void gatherCategory(IJeiRuntime rt, Job j, IRecipeCategory<R> category, List<IFocus<?>> focuses) {
        // only categories this container can take a recipe from, like JEI's "+" button
        if (rt.getRecipeTransferManager()
            .getRecipeTransferHandler(j.menu, category)
            .isEmpty()) return;
        rt.getRecipeManager()
            .createRecipeLookup(category.getRecipeType())
            .limitFocus(focuses)
            .get()
            .forEach(recipe -> {
                if (j.seen.add(recipe)) j.queue.add(() -> evaluate(rt, j, category, recipe));
            });
    }

    private static <R> void evaluate(IJeiRuntime rt, Job j, IRecipeCategory<R> category, R recipe) {
        Cached c = cache.get(recipe);
        if (c == null) {
            c = NONE;
            var layout = rt.getRecipeManager()
                .createRecipeLayoutDrawable(
                    category,
                    recipe,
                    rt.getJeiHelpers()
                        .getFocusFactory()
                        .getEmptyFocusGroup());
            if (layout.isPresent()) {
                var slots = layout.get()
                    .getRecipeSlotsView();
                String id = RecipeShape.idOf(category, recipe, slots);
                RecipeShape shape = RecipeShape.read(slots, id);
                if (!shape.outputs.isEmpty()) {
                    RecipeShape.Alt out = shape.outputs.get(0)
                        .chosen();
                    c = new Cached(new RecipeLookup.Target<>(category, recipe, slots),
                        McCompat.copyWithCount(out.stack(), out.count()), id);
                }
            }
            if (cache.size() >= MAX_CACHE) cache.clear();
            cache.put(recipe, c);
        }
        if (c != NONE && c.target.canTransfer(j.menu, j.player)) j.craftable.add(c);
    }

    private static List<Cached> publish(List<Cached> found) {
        Map<String, Cached> byItem = new LinkedHashMap<>();
        for (Cached c : found) {
            Cached old = byItem.get(c.outId);
            if (old == null || !old.favorite() && c.favorite()) byItem.put(c.outId, c);
        }
        List<Cached> list = new ArrayList<>(byItem.values());
        if (AddonConfig.CRAFTABLES_FAVORITES_ONLY.get()) list.removeIf(c -> !c.favorite());
        list.sort((a, b) -> Boolean.compare(b.favorite(), a.favorite()));
        return list;
    }

    // ---------------------------------------------------------------- layout

    private static boolean enabled(Screen s) {
        return AddonConfig.CRAFTABLES_ENABLED.get() && s instanceof AbstractContainerScreen<?> && QuantityField.visible(s);
    }

    private static int cols(Screen s) {
        return Math.max(1, (QuantityField.width(s) - 2 * PAD) / CELL);
    }

    private static int visibleRows(Screen s) {
        int rowsNeeded = (results.size() + cols(s) - 1) / cols(s);
        return Math.min(AddonConfig.CRAFTABLES_ROWS.get(), rowsNeeded);
    }

    /** The panel's rectangle, or null when it is hidden (off, or nothing can be crafted). */
    private static Rect2i rect(Screen s) {
        if (!enabled(s) || results.isEmpty()) return null;
        int w = cols(s) * CELL + 2 * PAD;
        int h = visibleRows(s) * CELL + 2 * PAD;
        int x = QuantityField.x(s);
        int y = QuantityField.y(s) - GAP_ABOVE_BAR - h;
        return new Rect2i(x, y, w, h);
    }

    public static List<Rect2i> extraAreas() {
        Rect2i r = rect(McCompat.screen());
        return r == null ? List.of() : List.of(r);
    }

    private static int indexAt(Screen s, double mx, double my) {
        Rect2i r = rect(s);
        if (r == null || mx < r.getX() + PAD || my < r.getY() + PAD || mx >= r.getX() + PAD + cols(s) * CELL
            || my >= r.getY() + PAD + visibleRows(s) * CELL) return -1;
        int col = (int) (mx - r.getX() - PAD) / CELL;
        int row = (int) (my - r.getY() - PAD) / CELL;
        int index = (scroll + row) * cols(s) + col;
        return index < results.size() ? index : -1;
    }

    /** The item of the cell under the mouse, for the hotkeys. */
    public static ItemStack hoveredStack() {
        Screen s = McCompat.screen();
        if (s == null || rect(s) == null) return ItemStack.EMPTY;
        int index = hoverIndex;
        return index >= 0 && index < results.size() ? McCompat.copyWithCount(results.get(index).out, 1) : ItemStack.EMPTY;
    }

    /** Shift + craft key over a cell: autocrafts one run of the cell's recipe. */
    public static boolean autocraft(ItemStack stack, boolean ignoreInventory) {
        if (hoverIndex < 0 || hoverIndex >= results.size()) return false;
        Cached c = results.get(hoverIndex);
        if (!c.outId.equals(RecipeData.idOf(stack))) return false;
        var layout = c.target.layout();
        if (layout == null) return false;
        RecipeShape shape = RecipeShape.read(layout);
        return AutoCraft.start(Bookmarks.autocraftRecipe(shape.toData(c.outId)), ignoreInventory);
    }

    // ---------------------------------------------------------------- drawing and input

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onForeground(ScreenEvent.Render.Post event) {
        Screen s = event.getScreen();
        hoverIndex = -1;
        if (!enabled(s)) return;
        tick((AbstractContainerScreen<?>) s);
        Rect2i r = rect(s);
        if (r == null) return;
        int pages = Math.max(0, (results.size() + cols(s) - 1) / cols(s) - visibleRows(s));
        scroll = Math.max(0, Math.min(scroll, pages));
        hoverIndex = indexAt(s, event.getMouseX(), event.getMouseY());
        GuiGraphics g = event.getGuiGraphics();
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.identity();
        pose.translateZ(100f);
        try {
            draw(g, s, r, event.getMouseX(), event.getMouseY());
        } finally {
            pose.popMatrix();
        }
    }

    private static void draw(GuiGraphics g, Screen s, Rect2i r, int mx, int my) {
        Font font = Minecraft.getInstance().font;
        g.fill(r.getX(), r.getY(), r.getX() + r.getWidth(), r.getY() + r.getHeight(), BACKGROUND);
        // NEI marks the top edge with a dashed line
        for (int x = r.getX(); x < r.getX() + r.getWidth(); x += 4) {
            g.fill(x, r.getY(), Math.min(x + 2, r.getX() + r.getWidth()), r.getY() + 1, 0xFFA0A0A0);
        }
        int cols = cols(s);
        for (int row = 0; row < visibleRows(s); row++) {
            for (int col = 0; col < cols; col++) {
                int index = (scroll + row) * cols + col;
                if (index >= results.size()) return;
                int x = r.getX() + PAD + col * CELL;
                int y = r.getY() + PAD + row * CELL;
                ItemStack out = results.get(index).out;
                if (index == hoverIndex) g.fill(x, y, x + CELL, y + CELL, HIGHLIGHT);
                g.renderFakeItem(out, x + 1, y + 1);
                g.renderItemDecorations(font, out, x + 1, y + 1, null);
            }
        }
        if (hoverIndex >= 0 && hoverIndex < results.size()) tooltip(g, font, results.get(hoverIndex), mx, my);
    }

    private static void tooltip(GuiGraphics g, Font font, Cached c, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        List<Component> body;
        HotkeyTips.building = true;
        try {
            body = new ArrayList<>(
                c.out.getTooltipLines(mc.player,
                    net.minecraft.world.item.TooltipFlag.Default.NORMAL));
        } finally {
            HotkeyTips.building = false;
        }
        List<Component> tip = new ArrayList<>(body);
        if (tip.isEmpty()) tip.add(c.out.getHoverName());
        tip.add(Component.literal(c.favorite() ? "§7Favorite recipe" : "§7Can be crafted now"));
        tip.add(HotkeyTips.modName(c.out));
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component line : tip) lines.addAll(font.split(line, 260));
        g.renderTooltip(font, lines, mx, my);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen s = event.getScreen();
        if (!enabled(s)) return;
        Rect2i r = rect(s);
        if (r == null || !r.contains((int) event.getMouseX(), (int) event.getMouseY())) return;
        int index = indexAt(s, event.getMouseX(), event.getMouseY());
        if (index >= 0 && event.getButton() <= 1) {
            BookmarkPanel.showRecipes(
                McCompat.copyWithCount(results.get(index).out, 1),
                event.getButton() == 0 ? RecipeIngredientRole.OUTPUT : RecipeIngredientRole.INPUT);
        }
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        Screen s = event.getScreen();
        Rect2i r = enabled(s) ? rect(s) : null;
        if (r == null || !r.contains((int) event.getMouseX(), (int) event.getMouseY())) return;
        scroll -= (int) Math.signum(event.getScrollDelta());
        event.setCanceled(true);
    }
}
