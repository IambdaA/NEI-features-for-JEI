package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IRecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import neiaddon.search.SearchQuery;
import org.lwjgl.glfw.GLFW;

/**
 * NEI's recipe search: a text field under JEI's recipe screen that narrows the recipes of the shown category to the
 * ones matching the query ({@link SearchQuery}: {@code <iron >rail}, {@code @mod}, {@code -not}, ...).
 *
 * <p>
 * JEI cannot filter its list, so the filtered recipes are shown with {@code IRecipesGui.showRecipes} on top of the
 * lookup the player started from, and clearing the field (or Escape) goes back to that lookup. Recipes are read for
 * searching a few milliseconds per frame, once per lookup. JEI's recipe screen internals (which lookup is shown) are
 * read by reflection on field and method types, so a JEI that changes them turns the field off with a log line.
 */
@EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT)
public final class RecipeFilter {

    private static final String RECIPES_GUI = "mezz.jei.gui.recipes.RecipesGui";
    private static final int H = 12;
    private static final long INDEX_NANOS = 2_000_000L;
    private static final long DEBOUNCE_MS = 250;
    private static final Object NONE = new Object();

    private static String text = "";
    private static List<SearchQuery.Clause> query = List.of();
    private static boolean focused;
    /** NEI: the field is hidden until the magnifier button is clicked. */
    private static boolean open;
    private static boolean failed;

    /** The lookup the filter started from and what it showed. */
    private static Object baseState;
    private static Object shownState;
    private static IRecipeCategory<?> baseCategory;
    private static List<Object> baseRecipes = List.of();
    private static List<IFocus<?>> baseFocuses = List.of();
    private static final Map<Object, Object> index = new IdentityHashMap<>();
    private static int cursor;

    private static boolean dirty;
    private static boolean applyPending;
    private static long applyAt;
    private static int matchCount = -1;
    private static Object owner;

    private static Field logicField;
    private static Field stateField;

    private RecipeFilter() {}

    private static boolean visible(Screen s) {
        return s != null && !failed && AddonConfig.RECIPE_SEARCH.get() && s.getClass()
            .getName()
            .equals(RECIPES_GUI) && s instanceof IRecipesGui && Calculator.runtime != null;
    }

    // ---------------------------------------------------------------- JEI internals (reflection)

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        for (Method m : target.getClass()
            .getMethods()) {
            if (m.getName()
                .equals(name) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                return m.invoke(target);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Method m : type.getMethods()) {
            if (m.getName()
                .equals(name)) return true;
        }
        return false;
    }

    /** The field of {@code from} whose type has a method called {@code marker} (found by type, not by JEI's field name). */
    private static Field fieldWith(Class<?> from, String marker) {
        for (Class<?> c = from; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType()
                    .isPrimitive() && hasMethod(f.getType(), marker)) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    private static Object logicOf(Screen s) throws ReflectiveOperationException {
        if (logicField == null) logicField = fieldWith(s.getClass(), "getSelectedRecipeCategory");
        if (logicField == null) throw new NoSuchFieldException("recipe logic");
        return logicField.get(s);
    }

    private static Object stateOf(Object logic) throws ReflectiveOperationException {
        if (stateField == null) stateField = fieldWith(logic.getClass(), "getFocusedRecipes");
        if (stateField == null) throw new NoSuchFieldException("lookup state");
        return stateField.get(logic);
    }

    private static Object stateOf(Screen s) throws ReflectiveOperationException {
        return stateOf(logicOf(s));
    }

    /** x, y, width, height of JEI's recipe window. */
    private static int[] area(Screen s) throws ReflectiveOperationException {
        Object a = call(s, "getArea");
        return new int[] { num(a, "getX", "x"), num(a, "getY", "y"), num(a, "getWidth", "width"), num(a, "getHeight", "height") };
    }

    private static int num(Object o, String... names) throws ReflectiveOperationException {
        for (String n : names) {
            try {
                return ((Number) call(o, n)).intValue();
            } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(names[0]);
    }

    private static void fail(String what, Throwable e) {
        failed = true;
        focused = false;
        NeiAddon.LOGGER.warn("Recipe search turned off, JEI's recipe screen differs from what it expects ({})", what, e);
    }

    // ---------------------------------------------------------------- state

    private static void reset() {
        text = "";
        query = List.of();
        focused = false;
        open = false;
        baseState = null;
        shownState = null;
        baseCategory = null;
        baseRecipes = List.of();
        baseFocuses = List.of();
        index.clear();
        cursor = 0;
        dirty = false;
        applyPending = false;
        matchCount = -1;
    }

    /** Called each frame: forgets the filter when the player moved JEI to another lookup. */
    private static void sync(Screen s) throws ReflectiveOperationException {
        Object rt = Calculator.runtime;
        if (owner != rt) {
            owner = rt;
            reset();
        }
        if (baseState == null) return;
        Object now = stateOf(s);
        // another lookup, or back to the start (JEI's own back button) while a filter was applied
        if (now != baseState && now != shownState || shownState != null && now == baseState) reset();
    }

    private static void setText(String next) {
        text = next.length() > 200 ? next.substring(0, 200) : next;
        query = SearchQuery.parseRecipe(text);
        matchCount = -1;
        dirty = !text.isBlank();
        applyPending = text.isBlank() && shownState != null;
        applyAt = System.currentTimeMillis() + DEBOUNCE_MS;
    }

    private static void captureBase(Screen s) throws ReflectiveOperationException {
        Object state = stateOf(s);
        Object fr = call(state, "getFocusedRecipes");
        baseCategory = (IRecipeCategory<?>) call(fr, "getRecipeCategory");
        baseRecipes = new ArrayList<>((List<?>) call(fr, "getRecipes"));
        baseFocuses = new ArrayList<>(((IFocusGroup) call(state, "getFocuses")).getAllFocuses());
        index.clear();
        cursor = 0;
        baseState = state;
    }

    /** Reads the next slice of the lookup's recipes into the search index. */
    private static void pump(Screen s) throws ReflectiveOperationException {
        if (!dirty) return;
        if (baseState == null) captureBase(s);
        long end = System.nanoTime() + INDEX_NANOS;
        while (cursor < baseRecipes.size() && System.nanoTime() < end) {
            Object recipe = baseRecipes.get(cursor++);
            Object entry = NONE;
            try {
                RecipeSearch.Entry e = RecipeSearch.build(Calculator.runtime, baseCategory, recipe);
                if (e != null) entry = e;
            } catch (RuntimeException ignored) {}
            index.put(recipe, entry);
        }
        if (cursor >= baseRecipes.size()) {
            dirty = false;
            applyPending = true;
        }
    }

    private static boolean indexing() {
        return dirty && baseState != null && cursor < baseRecipes.size();
    }

    // ---------------------------------------------------------------- applying (client tick, never inside rendering)

    private static void goBack(Screen s) throws ReflectiveOperationException {
        for (Method m : s.getClass()
            .getMethods()) {
            if (m.getName()
                .equals("back") && m.getParameterCount() == 0) {
                m.invoke(s);
                return;
            }
        }
        Object logic = logicOf(s);
        logic.getClass()
            .getMethod("back")
            .invoke(logic);
    }

    private static void restore(Screen s) throws ReflectiveOperationException {
        if (shownState == null) return;
        goBack(s);
        shownState = null;
        if (stateOf(s) != baseState) reset(); // going back did not land where the filter began
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void apply(Screen s) throws ReflectiveOperationException {
        applyPending = false;
        if (text.isBlank() || baseState == null) {
            restore(s);
            return;
        }
        List<Object> filtered = new ArrayList<>();
        for (Object recipe : baseRecipes) {
            if (index.get(recipe) instanceof RecipeSearch.Entry e && SearchQuery.matches(query, e)) filtered.add(recipe);
        }
        matchCount = filtered.size();
        if (filtered.isEmpty() || filtered.size() == baseRecipes.size()) {
            restore(s); // nothing to narrow to: keep (or go back to) the whole lookup
            return;
        }
        if (shownState != null) {
            restore(s);
            if (baseState == null) return;
        }
        ((IRecipesGui) s).showRecipes((IRecipeCategory) baseCategory, filtered, baseFocuses);
        Object now = stateOf(s);
        shownState = now == baseState ? null : now;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Screen s = McCompat.screen();
        if (!visible(s) || !applyPending || indexing() || System.currentTimeMillis() < applyAt) return;
        try {
            apply(s);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("apply", e);
        }
    }

    // ---------------------------------------------------------------- drawing

    private static final int TOGGLE_W = 12;
    private static final int PAGE_W = 45;

    /**
     * Where everything goes: the field (fx, fy, fw, fh) and, as in NEI, a magnifier button (tx, ty) and the page count
     * (px) in the page row between JEI's two page arrows. When JEI scrolls its recipes it has no page row, and the
     * field is then always shown on the recipe window's bottom border instead (row false).
     */
    private record Geo(boolean row, int fx, int fy, int fw, int fh, int tx, int ty, int tw, int px, int pw) {}

    private static Object fieldValue(Screen s, String name) throws ReflectiveOperationException {
        for (Class<?> c = s.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(s);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static Geo geo(Screen s) throws ReflectiveOperationException {
        Object logic = logicOf(s);
        Object prev = fieldValue(s, "previousPage");
        Object next = fieldValue(s, "nextPage");
        boolean scrolling;
        try {
            scrolling = Boolean.TRUE.equals(call(logic, "isScrolling"));
        } catch (NoSuchMethodException e) {
            scrolling = false; // JEI versions without scrolling recipes always have a page row
        }
        if (prev != null && next != null && !scrolling) {
            Object pa = call(prev, "getArea");
            Object na = call(next, "getArea");
            int tx = num(pa, "getX", "x") + num(pa, "getWidth", "width");
            int ty = num(pa, "getY", "y");
            int th = num(pa, "getHeight", "height");
            int fx = tx + TOGGLE_W;
            int pw = PAGE_W;
            int fw = num(na, "getX", "x") - pw - fx;
            if (fw >= 40) return new Geo(true, fx, ty, fw, th, tx, ty, TOGGLE_W, fx + fw, pw);
        }
        int[] a = area(s);
        // straddles the window's bottom border: JEI's recipes end 6 px above it, so the field never covers one
        int y = Math.min(a[1] + a[3] - 6, s.height - H - 1);
        if (JeiButtons.searchCentered()) y = Math.min(y, s.height - 24 - H - 2);
        return new Geo(false, a[0], y, a[2], H, 0, 0, 0, 0, 0);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static boolean fieldShown(Geo g) {
        return !g.row() || open;
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    static void onForeground(ScreenEvent.Render.Post event) {
        Screen s = event.getScreen();
        if (!visible(s)) return;
        try {
            sync(s);
            pump(s);
            Geo geo = geo(s);
            GuiGraphics g = event.getGuiGraphics();
            Pose pose = Pose.of(g);
            pose.pushMatrix();
            pose.identity();
            pose.translateZ(100f);
            try {
                draw(g, s, geo, event.getMouseX(), event.getMouseY());
            } finally {
                pose.popMatrix();
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("draw", e);
        }
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
        if (geo.row()) {
            boolean over = inside(mouseX, mouseY, geo.tx(), geo.ty(), geo.tw(), geo.fh());
            BookmarkPanel.button(g, font, geo.tx(), geo.ty(), geo.tw(), geo.fh(), "", over || open);
            magnifier(g, geo.tx() + 0, geo.ty() + (geo.fh() - 12) / 2, 0xFF202020);
            if (over) {
                g.renderTooltip(font, List.of(Component.literal(open ? "Close recipe search" : "Recipe search")
                    .getVisualOrderText()), mouseX, mouseY);
            }
        }
        if (!fieldShown(geo)) return;
        int x = geo.fx();
        int y = geo.fy();
        int w = geo.fw();
        int h = geo.fh();
        if (geo.row()) {
            // the page count moves to the right of the field, like NEI
            String page = String.valueOf(call(logicOf(s), "getPageString"));
            g.drawString(font, page, geo.px() + (geo.pw() - font.width(page)) / 2, y + (h - 8) / 2, 0xFFFFFFFF, true);
        }
        g.fill(x, y, x + w, y + h, focused ? 0xFFFFFFFF : 0xFFA0A0A0);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
        y += (h - H) / 2;

        String status = "";
        int statusColor = 0xFF909090;
        if (!text.isBlank()) {
            if (indexing()) {
                status = Math.round(cursor * 100f / Math.max(1, baseRecipes.size())) + "%";
            } else if (matchCount == 0) {
                status = "no match";
                statusColor = 0xFFFF5555;
            } else if (matchCount > 0) {
                status = matchCount + " / " + baseRecipes.size();
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

        if (inside(mouseX, mouseY, x, geo.fy(), w, h)) {
            List<FormattedCharSequence> tip = new ArrayList<>();
            for (String line : new String[] { "Recipe search", "<ingredient   >result   ^catalyst", "| or   - not   { } group", "@mod  #tooltip  $tag  &id  \"text\"  /regex/",
                "Right click clears", "Click the magnifier again to close" }) {
                tip.add(Component.literal(line)
                    .getVisualOrderText());
            }
            g.renderTooltip(font, tip, mouseX, mouseY);
        }
    }

    // ---------------------------------------------------------------- input

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen s = event.getScreen();
        if (!visible(s)) return;
        try {
            Geo geo = geo(s);
            double mx = event.getMouseX();
            double my = event.getMouseY();
            if (geo.row() && inside(mx, my, geo.tx(), geo.ty(), geo.tw(), geo.fh())) {
                if (event.getButton() == 0) {
                    if (open) {
                        setText("");
                        open = false;
                        focused = false;
                    } else {
                        open = true;
                        focused = true;
                    }
                }
                event.setCanceled(true);
                return;
            }
            if (!fieldShown(geo) || !inside(mx, my, geo.fx(), geo.fy(), geo.fw(), geo.fh())) {
                focused = false;
                return;
            }
            focused = true;
            if (event.getButton() == 1) {
                setText("");
                event.setCanceled(true);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("click", e);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!focused || !visible(event.getScreen())) return;
        event.setCanceled(true);
        int code = event.getKeyCode();
        Minecraft mc = Minecraft.getInstance();
        if (code == GLFW.GLFW_KEY_ESCAPE || code == GLFW.GLFW_KEY_ENTER || code == GLFW.GLFW_KEY_KP_ENTER) {
            focused = false;
        } else if (code == GLFW.GLFW_KEY_BACKSPACE) {
            if (McCompat.ctrl()) setText("");
            else if (!text.isEmpty()) setText(text.substring(0, text.length() - 1));
        } else if (code == GLFW.GLFW_KEY_V && McCompat.ctrl()) {
            setText(text + mc.keyboardHandler.getClipboard()
                .replaceAll("[\\r\\n\\t]+", " "));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onChar(ScreenEvent.CharacterTyped.Pre event) {
        if (!focused || !visible(event.getScreen())) return;
        event.setCanceled(true);
        int c = event.getCodePoint();
        if (c >= ' ' && c != 127) setText(text + new String(Character.toChars(c)));
    }
}
