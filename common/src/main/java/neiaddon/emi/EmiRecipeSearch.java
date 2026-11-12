package neiaddon.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import neiaddon.AddonConfig;
import neiaddon.EmiCompat;
import neiaddon.NeiAddon;
import neiaddon.RecipeData;
import neiaddon.SearchItem;
import neiaddon.search.SearchQuery;
import org.lwjgl.glfw.GLFW;

/**
 * The recipe search on EMI's recipe screen: the field and magnifier of {@code RecipeFilter}, drawn in EMI's page bar between
 * its two page arrows, with the page count to the right of the field. EMI shows every category of a lookup on one screen,
 * so the search narrows all of them: it swaps the recipe map of EMI's internal recipe screen (by reflection) for one with
 * only the matching recipes (categories with no match left out), and re-initialises the screen as a window resize does.
 * The whole map goes back when the text is cleared, when nothing or everything matches, and when the screen is left, before
 * EMI keeps the screen in its history. Each recipe is read once per screen, a few milliseconds per frame, as slots of items
 * ({@link SearchItem}). A failure turns the field off with one warning (see {@link #fail}). The plugin
 * ({@link NeiAddonEmiPlugin}) sets this as {@link EmiCompat#recipeSearch}.
 */
final class EmiRecipeSearch implements EmiCompat.RecipeSearchHooks {

    private static final int H = 12;
    private static final int TOGGLE_W = 12;
    /** The width of the page count at the right end of the page bar (as {@code RecipeFilter.PAGE_W}). */
    private static final int PAGE_W = 45;
    /** The narrowest field worth drawing; a narrower one is left out (no warning). */
    private static final int MIN_FIELD = 40;
    private static final int MAX_ALTERNATIVES = 32;
    private static final long INDEX_NANOS = 2_000_000L;
    private static final long DEBOUNCE_MS = 250;
    private static final Object NONE = new Object();

    private static boolean failed;
    private static boolean warnedIndex;

    /** The recipe screen the state belongs to, and whether it had a recipe map when the state began. */
    private static Screen screen;
    private static boolean hasRecipes;
    /** The screen's recipe map when the state began: what it goes back to. */
    private static Map<EmiRecipeCategory, List<EmiRecipe>> original = Map.of();
    /** Whether the screen shows a filtered map now. */
    private static boolean filtered;

    private static String text = "";
    private static List<SearchQuery.Clause> query = List.of();
    private static boolean focused;
    /** NEI: the field is hidden until the magnifier button is clicked. */
    private static boolean open;
    private static final Map<EmiRecipe, Object> index = new IdentityHashMap<>();
    /** Every recipe of {@link #original}, in map order, and how many of them are indexed. */
    private static List<EmiRecipe> all = List.of();
    private static int cursor;

    private static boolean dirty;
    private static boolean applyPending;
    private static long applyAt;
    private static int matchCount = -1;

    private static Field recipesField;
    private static Field tabsField;
    private static Field tabField;
    private static Field pageField;
    private static Field arrowsField;
    private static Method focusedMethod;
    private static Method focusMethod;
    private static Method pageCountMethod;

    EmiRecipeSearch() {}

    /** Whether the field is on at all: not failed, and the setting is on. */
    private static boolean enabled() {
        return !failed && AddonConfig.RECIPE_SEARCH.get();
    }

    // ---------------------------------------------------------------- EMI's recipe screen (reflection)

    private static Field declared(Object target, String name) throws NoSuchFieldException {
        Field f = target.getClass()
            .getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Looks up EMI's internal fields and methods once; a missing one throws (the field is then turned off). */
    private static void initFields(Screen s) throws ReflectiveOperationException {
        if (recipesField != null) return;
        Field recipes = declared(s, "recipes");
        Field tabs = declared(s, "tabs");
        Field tab = declared(s, "tab");
        Field page = declared(s, "page");
        Field arrows = declared(s, "arrows");
        Method focused = s.getClass()
            .getMethod("getFocusedCategory");
        focused.setAccessible(true);
        Method focus = s.getClass()
            .getMethod("focusCategory", EmiRecipeCategory.class);
        focus.setAccessible(true);
        recipesField = recipes;
        tabsField = tabs;
        tabField = tab;
        pageField = page;
        arrowsField = arrows;
        focusedMethod = focused;
        focusMethod = focus;
    }

    /** The page count of a tab (EMI's RecipeTab), its method looked up once per tab class. */
    private static Method pageCount(Object tab) throws NoSuchMethodException {
        if (pageCountMethod == null || !pageCountMethod.getDeclaringClass()
            .isInstance(tab)) {
            Method m = tab.getClass()
                .getMethod("getPageCount");
            m.setAccessible(true);
            pageCountMethod = m;
        }
        return pageCountMethod;
    }

    @SuppressWarnings("unchecked")
    private static Map<EmiRecipeCategory, List<EmiRecipe>> recipesOf(Screen s) throws ReflectiveOperationException {
        return (Map<EmiRecipeCategory, List<EmiRecipe>>) recipesField.get(s);
    }

    /**
     * Puts a recipe map on the screen and re-initialises the screen as a window resize does; the focused category is kept
     * when the new map has it (EMI's init shows the first category otherwise).
     */
    private static void show(Screen s, Map<EmiRecipeCategory, List<EmiRecipe>> map) throws ReflectiveOperationException {
        EmiRecipeCategory before = (EmiRecipeCategory) focusedMethod.invoke(s);
        recipesField.set(s, map);
        s.resize(Minecraft.getInstance(), s.width, s.height);
        if (before != null && map.containsKey(before)) focusMethod.invoke(s, before);
    }

    /** The page of EMI's recipe screen as "page / pages", or null when it cannot be read (the box is then left empty). */
    private static String pageText(Screen s) {
        try {
            List<?> tabs = (List<?>) tabsField.get(s);
            Object tab = tabs.get(tabField.getInt(s));
            Object count = pageCount(tab).invoke(tab);
            return (pageField.getInt(s) + 1) + "/" + count;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- state

    private static void fail(String what, Throwable e) {
        failed = true;
        focused = false;
        if (filtered && screen != null && recipesField != null) {
            // give the whole lookup back, without re-initialising: the field is off from now on
            try {
                recipesField.set(screen, original);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // the screen is not readable any more; nothing else can be done here
            }
            filtered = false;
        }
        NeiAddon.LOGGER.warn("Recipe search turned off on EMI's recipe screen, it differs from what the addon expects ({})", what, e);
    }

    private static void reset() {
        text = "";
        query = List.of();
        focused = false;
        open = false;
        filtered = false;
        index.clear();
        all = List.of();
        cursor = 0;
        dirty = false;
        applyPending = false;
        applyAt = 0;
        matchCount = -1;
    }

    /** Starts the state over for another screen (or none): the screen's map is not touched here. */
    private static void forget() {
        reset();
        screen = null;
        original = Map.of();
        hasRecipes = false;
    }

    /** Starts the state for a screen: its map is read as the whole lookup. */
    private static void begin(Screen s) throws ReflectiveOperationException {
        initFields(s);
        if (filtered && screen != null) recipesField.set(screen, original); // an older screen that was never left
        reset();
        screen = s;
        Map<EmiRecipeCategory, List<EmiRecipe>> map = recipesOf(s);
        hasRecipes = map != null;
        original = map == null ? Map.of() : map;
        List<EmiRecipe> list = new ArrayList<>();
        for (List<EmiRecipe> recipes : original.values()) list.addAll(recipes);
        all = list;
    }

    /** The state for this screen: it starts over when the screen is another one. */
    private static void own(Screen s) throws ReflectiveOperationException {
        if (s != screen) begin(s);
    }

    private static void setText(String next) {
        text = next.length() > 200 ? next.substring(0, 200) : next;
        query = SearchQuery.parseRecipe(text);
        matchCount = -1;
        dirty = !text.isBlank();
        applyPending = text.isBlank() && filtered;
        applyAt = System.currentTimeMillis() + DEBOUNCE_MS;
    }

    private static boolean indexing() {
        return dirty && cursor < all.size();
    }

    // ---------------------------------------------------------------- indexing

    /** One recipe as the search sees it: its slots, each a list of the items it accepts. */
    private static final class Searchable implements SearchQuery.Recipe {

        final List<List<SearchQuery.Item>> in = new ArrayList<>();
        final List<List<SearchQuery.Item>> out = new ArrayList<>();
        final List<List<SearchQuery.Item>> other = new ArrayList<>();

        @Override
        public List<List<SearchQuery.Item>> ingredients() {
            return in;
        }

        @Override
        public List<List<SearchQuery.Item>> results() {
            return out;
        }

        @Override
        public List<List<SearchQuery.Item>> others() {
            return other;
        }
    }

    /** Each ingredient is one slot: its items (EMI's stacks that are items, no duplicates, at most 32); a slot with none is left out. */
    private static void slots(List<? extends EmiIngredient> ingredients, List<List<SearchQuery.Item>> into) {
        if (ingredients == null) return;
        for (EmiIngredient ingredient : ingredients) {
            List<SearchQuery.Item> alts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (EmiStack stack : ingredient.getEmiStacks()) {
                if (alts.size() >= MAX_ALTERNATIVES) break;
                ItemStack item = stack.getItemStack();
                if (item == null || item.isEmpty() || !seen.add(RecipeData.idOf(item))) continue;
                alts.add(SearchItem.of(item));
            }
            if (!alts.isEmpty()) into.add(alts);
        }
    }

    /** The searchable form of a recipe; NONE (never matches) when it has no item slot or cannot be read (one warning). */
    private static Object indexOf(EmiRecipe recipe) {
        try {
            Searchable found = new Searchable();
            slots(recipe.getInputs(), found.in);
            slots(recipe.getOutputs(), found.out);
            slots(recipe.getCatalysts(), found.other);
            return found.in.isEmpty() && found.out.isEmpty() && found.other.isEmpty() ? NONE : found;
        } catch (RuntimeException | LinkageError e) {
            if (!warnedIndex) {
                warnedIndex = true;
                NeiAddon.LOGGER.warn("Could not read a recipe of EMI for the recipe search; it is left out of the search", e);
            }
            return NONE;
        }
    }

    /** Indexes the next slice of the recipes (a few milliseconds per frame, only while the text is being searched). */
    private static void pump() {
        if (!dirty) return;
        long end = System.nanoTime() + INDEX_NANOS;
        while (cursor < all.size() && System.nanoTime() < end) {
            EmiRecipe recipe = all.get(cursor++);
            index.put(recipe, indexOf(recipe));
        }
        if (cursor >= all.size()) {
            dirty = false;
            applyPending = true;
        }
    }

    // ---------------------------------------------------------------- applying (client tick, never while drawing)

    /** Puts the whole lookup back when the screen shows a filtered one. */
    private static void restore(Screen s) throws ReflectiveOperationException {
        if (!filtered) return;
        show(s, original);
        filtered = false;
    }

    /** Narrows the screen to the matching recipes, categories without a match left out; the whole lookup when none or all match. */
    private static void apply(Screen s) throws ReflectiveOperationException {
        applyPending = false;
        if (text.isBlank()) {
            restore(s);
            return;
        }
        Map<EmiRecipeCategory, List<EmiRecipe>> out = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<EmiRecipeCategory, List<EmiRecipe>> e : original.entrySet()) {
            List<EmiRecipe> keep = new ArrayList<>();
            for (EmiRecipe recipe : e.getValue()) {
                if (index.get(recipe) instanceof Searchable found && SearchQuery.matches(query, found)) keep.add(recipe);
            }
            if (!keep.isEmpty()) {
                out.put(e.getKey(), keep);
                count += keep.size();
            }
        }
        matchCount = count;
        if (count == 0 || count == all.size()) {
            restore(s); // nothing to narrow to: keep (or go back to) the whole lookup
            return;
        }
        filtered = true; // before the write: if re-initialising throws, fail() and leaving() still put the whole lookup back
        show(s, out);
    }

    // ---------------------------------------------------------------- geometry and drawing

    /** The page bar: the toggle at bx, the field from bx + 12 to bend - 45, the page count from bend - 45 to bend. */
    private record Geo(int bx, int y, int h, int fw, int bend) {}

    /** Where the bar is, read from EMI's page arrows every frame (EMI moves them when the tab is wider); null when too narrow. */
    private static Geo geo(Screen s) throws ReflectiveOperationException {
        List<?> arrows = (List<?>) arrowsField.get(s);
        AbstractWidget prev = (AbstractWidget) arrows.get(4);
        AbstractWidget next = (AbstractWidget) arrows.get(5);
        int bx = prev.getX() + prev.getWidth() + 2;
        int bend = next.getX() - 2;
        int fw = bend - PAGE_W - (bx + TOGGLE_W);
        if (fw < MIN_FIELD) return null;
        return new Geo(bx, prev.getY(), prev.getHeight(), fw, bend);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** The magnifier: a small ring with a handle. */
    private static void magnifier(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 4, y + 2, x + 8, y + 3, color);
        g.fill(x + 4, y + 7, x + 8, y + 8, color);
        g.fill(x + 3, y + 3, x + 4, y + 7, color);
        g.fill(x + 8, y + 3, x + 9, y + 7, color);
        g.fill(x + 8, y + 7, x + 9, y + 8, color);
        g.fill(x + 9, y + 8, x + 10, y + 9, color);
        g.fill(x + 10, y + 9, x + 11, y + 10, color);
    }

    private static void draw(GuiGraphics g, Screen s, Geo geo, int mouseX, int mouseY) throws ReflectiveOperationException {
        Font font = Minecraft.getInstance().font;
        int bx = geo.bx();
        int y = geo.y();
        int h = geo.h();
        int x = bx + TOGGLE_W;
        int bend = geo.bend();
        boolean over = inside(mouseX, mouseY, bx, y, TOGGLE_W, h);
        EmiCompat.button(g, font, bx, y, TOGGLE_W, h, over || open);
        magnifier(g, bx, y + (h - 12) / 2, 0xFF202020);
        if (over) {
            g.renderTooltip(font, List.of(Component.literal(open ? "Close recipe search" : "Recipe search")
                .getVisualOrderText()), mouseX, mouseY);
        }
        if (!open) return;
        // EMI's page text sits in this bar: cover it before the field and the page count are drawn
        g.fill(x, y, bend, y + h, 0xFF000000);
        field(g, font, x, y, geo.fw(), h, mouseX, mouseY);
        int px = bend - PAGE_W;
        g.fill(px, y, bend, y + h, 0xFFA0A0A0);
        g.fill(px + 1, y + 1, bend - 1, y + h - 1, 0xFF000000);
        String page = pageText(s);
        if (page != null) g.drawString(font, page, px + (PAGE_W - font.width(page)) / 2, y + (h - 8) / 2, 0xFFFFFFFF, true);
    }

    /** The field as {@code RecipeFilter} draws it: border, status, placeholder or text with the cursor, and its tooltip. */
    private static void field(GuiGraphics g, Font font, int x, int top, int w, int h, int mouseX, int mouseY) {
        g.fill(x, top, x + w, top + h, focused ? 0xFFFFFFFF : 0xFFA0A0A0);
        g.fill(x + 1, top + 1, x + w - 1, top + h - 1, 0xFF000000);
        int y = top + (h - H) / 2;

        String status = "";
        int statusColor = 0xFF909090;
        if (!text.isBlank()) {
            if (indexing()) {
                status = Math.round(cursor * 100f / Math.max(1, all.size())) + "%";
            } else if (matchCount == 0) {
                status = "no match";
                statusColor = 0xFFFF5555;
            } else if (matchCount > 0) {
                status = matchCount + " / " + all.size();
            }
        }
        int statusW = status.isEmpty() ? 0 : font.width(status) + 6;
        if (!status.isEmpty()) g.drawString(font, status, x + w - statusW + 1, y + 2, statusColor, true);

        if (text.isEmpty() && !focused) {
            g.drawString(font, "Search recipes", x + 4, y + 2, 0xFF707070, false);
        } else {
            String shown = font.plainSubstrByWidth(text, w - 8 - statusW, true) + (focused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "");
            g.drawString(font, shown, x + 4, y + 2, 0xFFE0E0E0, true);
        }

        if (inside(mouseX, mouseY, x, top, w, h)) {
            List<FormattedCharSequence> tip = new ArrayList<>();
            for (String line : new String[] { "Recipe search", "<ingredient   >result   ^catalyst", "| or   - not   { } group", "@mod  #tooltip  $tag  &id  \"text\"  /regex/",
                "Right click clears", "Click the magnifier again to close" }) {
                tip.add(Component.literal(line)
                    .getVisualOrderText());
            }
            g.renderTooltip(font, tip, mouseX, mouseY);
        }
    }

    /** The state for the test driver: what the field shows and what the screen shows now (as text). */
    static String describe() {
        Screen shown = Minecraft.getInstance().screen;
        int categories = -1;
        try {
            if (EmiCompat.isRecipeScreen(shown) && recipesField != null) {
                Map<?, ?> map = recipesOf(shown);
                categories = map == null ? 0 : map.size();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            categories = -2;
        }
        return "open=" + open + " focused=" + focused + " text=\"" + text + "\" matchCount=" + matchCount + " total=" + all.size()
            + " filtered=" + filtered + " categories=" + categories + " failed=" + failed;
    }

    // ---------------------------------------------------------------- hooks

    @Override
    public void tick(Screen s) {
        if (!enabled()) return;
        try {
            own(s);
            if (!hasRecipes || !applyPending || indexing() || System.currentTimeMillis() < applyAt) return;
            apply(s);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("apply", e);
        }
    }

    @Override
    public void render(Screen s, GuiGraphics g, int mouseX, int mouseY) {
        if (!enabled()) return;
        try {
            own(s);
            if (!hasRecipes) return;
            pump();
            Geo geo = geo(s);
            if (geo == null) {
                focused = false; // the field isn't shown (too narrow): it takes no keys either
                return;
            }
            g.pose()
                .pushPose();
            g.pose()
                .translate(0.0, 0.0, 100.0);
            try {
                draw(g, s, geo, mouseX, mouseY);
            } finally {
                g.pose()
                    .popPose();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("draw", e);
        }
    }

    @Override
    public boolean click(Screen s, double mouseX, double mouseY, int button) {
        if (!enabled()) return false;
        try {
            own(s);
            if (!hasRecipes) return false;
            Geo geo = geo(s);
            if (geo == null) {
                focused = false;
                return false;
            }
            if (inside(mouseX, mouseY, geo.bx(), geo.y(), TOGGLE_W, geo.h())) {
                if (button == 0) {
                    if (open) {
                        setText("");
                        open = false;
                        focused = false;
                    } else {
                        open = true;
                        focused = true;
                    }
                }
                return true;
            }
            int x = geo.bx() + TOGGLE_W;
            if (!open || !inside(mouseX, mouseY, x, geo.y(), geo.fw(), geo.h())) {
                focused = false;
                return false;
            }
            focused = true;
            if (button == 1) setText("");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("click", e);
            return false;
        }
    }

    @Override
    public boolean key(Screen s, int keyCode) {
        if (!enabled() || !focused || s != screen) return false;
        try {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                focused = false;
            } else if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (Screen.hasControlDown()) setText("");
                else if (!text.isEmpty()) setText(text.substring(0, text.length() - 1));
            } else if (keyCode == GLFW.GLFW_KEY_V && Screen.hasControlDown()) {
                setText(text + Minecraft.getInstance().keyboardHandler.getClipboard()
                    .replaceAll("[\\r\\n\\t]+", " "));
            }
            return true;
        } catch (RuntimeException | LinkageError e) {
            fail("key", e);
            return false;
        }
    }

    @Override
    public boolean typed(Screen s, int codePoint) {
        if (!enabled() || !focused || s != screen) return false;
        try {
            if (codePoint >= ' ' && codePoint != 127) setText(text + new String(Character.toChars(codePoint)));
            return true;
        } catch (RuntimeException | LinkageError e) {
            fail("typed", e);
            return false;
        }
    }

    @Override
    public void leaving(Screen s) {
        if (s != screen) return;
        try {
            if (filtered && !failed) {
                // no re-initialise: EMI's history may bring this screen back, and then it starts from the whole lookup
                recipesField.set(s, original);
                filtered = false;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("leaving", e);
        }
        forget();
    }
}
