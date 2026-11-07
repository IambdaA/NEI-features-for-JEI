package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Stream;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

/**
 * Where JEI's item list and lookup history really are (they can be dragged to resize), read from JEI by reflection:
 * {@code IngredientListOverlay.contents.getBackgroundArea()} and {@code lookupHistoryOverlay}. Returns null when JEI's
 * internals differ; callers then fall back to a fixed layout.
 */
public final class JeiAreas {

    // one flag per feature, so one broken lookup cannot switch the others off
    private static boolean failedList, failedHistory, failedMatch, failedHeight;
    private static Field contentsField, historyField;
    private static Method contentsArea;

    private JeiAreas() {}

    /** The item list column (grid and page buttons), or null. */
    public static Rect2i list() {
        if (failedList || Calculator.runtime == null) return null;
        try {
            Object overlay = Calculator.runtime.getIngredientListOverlay();
            if (contentsField == null) {
                contentsField = field(overlay.getClass(), "contents");
            }
            Object contents = contentsField.get(overlay);
            if (contentsArea == null) {
                contentsArea = contents.getClass()
                    .getMethod("getBackgroundArea");
                contentsArea.setAccessible(true);
            }
            return rect(contentsArea.invoke(contents));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("list", e);
            failedList = true;
            return null;
        }
    }

    /** JEI's lookup history list while it is shown, or null. */
    public static Rect2i history() {
        if (failedHistory || Calculator.runtime == null) return null;
        try {
            Object history = shownHistory();
            return history == null ? null : rect(historyArea(history));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("history", e);
            failedHistory = true;
            return null;
        }
    }

    private static Field bookmarkHistoryField;
    private static boolean noBookmarkHistory;

    /**
     * The lookup history overlay that is shown, or null: the item list's, else the one on the bookmark side (JEI 15.59 and
     * later on 1.20.1 give each side its own).
     */
    private static Object shownHistory() throws ReflectiveOperationException {
        Object listOverlay = Calculator.runtime.getIngredientListOverlay();
        if (historyField == null) historyField = field(listOverlay.getClass(), "lookupHistoryOverlay");
        Object history = historyField.get(listOverlay);
        if (history != null && Boolean.TRUE.equals(invoke(history, "isListDisplayed"))) return history;
        Object bookmarks = Calculator.runtime.getBookmarkOverlay();
        if (bookmarks == null || noBookmarkHistory) return null;
        if (bookmarkHistoryField == null) {
            try {
                bookmarkHistoryField = field(bookmarks.getClass(), "lookupHistoryOverlay");
            } catch (NoSuchFieldException e) {
                noBookmarkHistory = true;
                return null;
            }
        }
        Object other = bookmarkHistoryField.get(bookmarks);
        return other != null && other != history && Boolean.TRUE.equals(invoke(other, "isListDisplayed")) ? other : null;
    }

    /** Where a history overlay is: its own getBackgroundArea(), its backgroundArea field (JEI 15.59), or its grid's area. */
    private static Object historyArea(Object history) throws ReflectiveOperationException {
        if (method(history.getClass(), "getBackgroundArea") != null) return invoke(history, "getBackgroundArea");
        Field area = cachedField(history.getClass(), "backgroundArea");
        if (area != null) return area.get(history);
        Field contents = cachedField(history.getClass(), "contents");
        if (contents == null) throw new NoSuchFieldException("backgroundArea / contents of " + history.getClass().getName());
        return invoke(contents.get(history), "getBackgroundArea");
    }

    // ---------------------------------------------------------------- reflection helpers (cached, misses too)

    private static final Map<String, Optional<Method>> METHODS = new HashMap<>();
    private static final Map<String, Optional<Field>> FIELDS = new HashMap<>();

    /** A method without parameters (public, or declared on the class or a superclass), or null. */
    static Method method(Class<?> type, String name) {
        return METHODS.computeIfAbsent(type.getName() + '#' + name, k -> {
            Method m = null;
            try {
                m = type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                for (Class<?> c = type; c != null && m == null; c = c.getSuperclass()) {
                    try {
                        m = c.getDeclaredMethod(name);
                    } catch (NoSuchMethodException ignored2) {}
                }
            }
            if (m != null) m.setAccessible(true);
            return Optional.ofNullable(m);
        })
            .orElse(null);
    }

    private static Field cachedField(Class<?> type, String name) {
        return FIELDS.computeIfAbsent(type.getName() + '.' + name, k -> {
            try {
                return Optional.of(field(type, name));
            } catch (NoSuchFieldException e) {
                return Optional.empty();
            }
        })
            .orElse(null);
    }

    /** Calls the first of these parameterless methods the object has (JEI renames them between versions). */
    static Object invoke(Object o, String... names) throws ReflectiveOperationException {
        for (String name : names) {
            Method m = method(o.getClass(), name);
            if (m != null) return m.invoke(o);
        }
        throw new NoSuchMethodException(o.getClass()
            .getName() + "." + String.join(" / ", names) + "()");
    }

    /** True while JEI is asked for the areas other mods keep free; our own area handler answers nothing then. */
    static volatile boolean querying;

    private static boolean failedExclusions;
    private static Object exclusionOwner;
    private static Method exclusionMethod;
    private static Screen exclusionScreen;
    private static long exclusionStamp;
    private static List<Rect2i> exclusionCache = List.of();

    /**
     * The areas of the screen that other mods (FTB Quests and Teams, and so on) have asked JEI to keep free: the same list
     * JEI moves its own overlays out of. Read from JEI's screen helper ({@code getGuiExclusionAreas(Screen)}), found by
     * name below the runtime, so a JEI that differs just gives an empty list (one logged warning). Cached for a moment
     * because the panel layout asks every frame.
     */
    public static List<Rect2i> exclusions(Screen screen) {
        if (querying || failedExclusions || Calculator.runtime == null || screen == null) return List.of();
        long now = System.currentTimeMillis();
        if (screen == exclusionScreen && now - exclusionStamp < 250) return exclusionCache;
        querying = true;
        try {
            if (exclusionMethod == null) findExclusions();
            Object result = exclusionMethod.invoke(exclusionOwner, screen);
            List<Rect2i> out = new ArrayList<>();
            if (result instanceof Iterable<?> areas) {
                for (Object area : areas) {
                    Rect2i r = rect(area);
                    if (r.getWidth() > 0 && r.getHeight() > 0) out.add(r);
                }
            }
            exclusionScreen = screen;
            exclusionStamp = now;
            exclusionCache = out;
            return out;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("exclusions", e);
            failedExclusions = true;
            return List.of();
        } finally {
            querying = false;
        }
    }

    private static void findExclusions() throws ReflectiveOperationException {
        List<Object> roots = new ArrayList<>();
        try {
            Object helper = Calculator.runtime.getClass()
                .getMethod("getScreenHelper")
                .invoke(Calculator.runtime);
            if (helper != null) roots.add(helper);
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
        roots.add(Calculator.runtime);
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        for (Object root : roots) {
            if (seen.add(root)) queue.add(new Object[] { root, 0 });
        }
        while (!queue.isEmpty()) {
            Object[] entry = queue.poll();
            Object o = entry[0];
            int depth = (Integer) entry[1];
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName()
                        .equals("getGuiExclusionAreas") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(Screen.class)) {
                        m.setAccessible(true);
                        exclusionOwner = o;
                        exclusionMethod = m;
                        return;
                    }
                }
                if (depth >= 3) continue;
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType()
                        .isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null) continue;
                        String name = v.getClass()
                            .getName();
                        if (name.startsWith("java.") || name.startsWith("net.minecraft.") || name.startsWith("com.mojang.")) continue;
                        if (seen.add(v)) queue.add(new Object[] { v, depth + 1 });
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        throw new NoSuchMethodException("getGuiExclusionAreas");
    }

    private static boolean failedHover;
    private static Method historyHover;

    /**
     * The item under the mouse in JEI's lookup history list, while that list is shown and the mouse is over it. Asked
     * of the history overlay itself ({@code getIngredientUnderMouse(double, double)}), so an empty result simply means
     * "not over an item", and a JEI whose internals differ switches this off after one logged failure.
     */
    public static Optional<ItemStack> historyHovered(int mouseX, int mouseY) {
        if (failedHover || Calculator.runtime == null) return Optional.empty();
        try {
            Rect2i area = history();
            if (area == null || !area.contains(mouseX, mouseY)) return Optional.empty();
            Object history = shownHistory();
            if (history == null) return Optional.empty();
            if (historyHover == null) {
                for (Method m : history.getClass()
                    .getMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName()
                        .equals("getIngredientUnderMouse") && p.length == 2 && p[0] == double.class && p[1] == double.class) {
                        historyHover = m;
                        break;
                    }
                }
                if (historyHover == null) throw new NoSuchMethodException("getIngredientUnderMouse(double, double)");
                historyHover.setAccessible(true);
            }
            return itemOf(historyHover.invoke(history, (double) mouseX, (double) mouseY));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("historyHovered", e);
            failedHover = true;
            return Optional.empty();
        }
    }

    /** Unwraps what JEI's lookup gives (an Optional or a Stream of a typed or clickable ingredient) to an item. */
    private static Optional<ItemStack> itemOf(Object result) throws ReflectiveOperationException {
        if (result instanceof Optional<?> o) result = o.orElse(null);
        else if (result instanceof Stream<?> st) result = st.findFirst()
            .orElse(null);
        if (result == null) return Optional.empty();
        if (!(result instanceof ITypedIngredient<?>)) {
            Method m = result.getClass()
                .getMethod("getTypedIngredient");
            m.setAccessible(true);
            result = m.invoke(result);
        }
        return result instanceof ITypedIngredient<?> t ? t.getItemStack() : Optional.empty();
    }

    private static Field historyConfigField;
    private static Method gridArea, valueGet, valueSet;
    private static long lastMatch;
    private static int pendingColumns = -1;

    /**
     * Sets JEI's history list to as many columns as the item list shows (JEI keeps the two widths separately). Runs at
     * most twice a second; does nothing when the history is off, on the other side, or JEI's internals differ.
     */
    public static void matchHistoryWidth() {
        long now = System.currentTimeMillis();
        if (failedMatch || Calculator.runtime == null || now - lastMatch < 500) return;
        lastMatch = now;
        try {
            Object overlay = Calculator.runtime.getIngredientListOverlay();
            // only the history on the item list's side (JEI 15.59 also has one on the bookmark side)
            if (historyField == null) historyField = field(overlay.getClass(), "lookupHistoryOverlay");
            Object shown = historyField.get(overlay);
            if (shown == null || !Boolean.TRUE.equals(invoke(shown, "isListDisplayed"))) return;
            if (contentsField == null) contentsField = field(overlay.getClass(), "contents");
            Object contents = contentsField.get(overlay);
            if (gridArea == null) {
                try {
                    gridArea = contents.getClass()
                        .getMethod("getIngredientGridArea");
                } catch (NoSuchMethodException e) {
                    gridArea = contents.getClass()
                        .getMethod("getBackgroundArea");
                }
                gridArea.setAccessible(true);
            }
            int columns = num(gridArea.invoke(contents), "width", "getWidth") / 18;
            if (columns < 1) return;
            // JEI lays the list out a moment after the screen opens or a setting changes: only trust a value seen twice in a row
            if (columns != pendingColumns) {
                pendingColumns = columns;
                return;
            }
            Object history = historyField.get(overlay);
            Object config = historyConfig(history);
            Method columnsGetter = method(config.getClass(), "maxColumns");
            Object value = columnsGetter != null ? columnsGetter.invoke(config) : cachedField(config.getClass(), "maxColumns").get(config);
            if (valueGet == null) {
                valueGet = value.getClass()
                    .getMethod("get");
                valueGet.setAccessible(true);
                for (Method m : value.getClass()
                    .getMethods()) {
                    if (m.getName()
                        .equals("set") && m.getParameterCount() == 1 && !m.getParameterTypes()[0].isPrimitive()) valueSet = m;
                }
                if (valueSet == null) throw new NoSuchMethodException("set");
                valueSet.setAccessible(true);
            }
            if (((Number) valueGet.invoke(value)).intValue() != columns) valueSet.invoke(value, columns);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("matchHistoryWidth", e);
            failedMatch = true;
        }
    }

    private static Method historyHeight, historyShownForHeight;

    /**
     * Height of JEI's lookup history band (it sits just above JEI's search row, across the whole column) while it is
     * shown, else 0. It comes from JEI's config, not from where things were drawn, so using it does not feed back.
     */
    public static int historyHeight() {
        if (failedHeight || Calculator.runtime == null) return 0;
        try {
            Object overlay = Calculator.runtime.getIngredientListOverlay();
            if (historyField == null) historyField = field(overlay.getClass(), "lookupHistoryOverlay");
            Object history = historyField.get(overlay);
            if (historyHeight == null) {
                historyHeight = history.getClass()
                    .getMethod("getDisplayHeight");
                historyHeight.setAccessible(true);
            }
            if (historyShownForHeight == null) {
                historyShownForHeight = history.getClass()
                    .getMethod("isListDisplayed");
                historyShownForHeight.setAccessible(true);
            }
            if (!Boolean.TRUE.equals(historyShownForHeight.invoke(history))) return 0;
            return ((Number) historyHeight.invoke(history)).intValue();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("historyHeight", e);
            failedHeight = true;
            return 0;
        }
    }

    /** The history's grid config: JEI's field name for it may differ, so take the first field that has maxColumns(). */
    private static Object historyConfig(Object history) throws ReflectiveOperationException {
        if (historyConfigField == null) {
            for (Class<?> c = history.getClass(); c != null && historyConfigField == null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(history);
                        // a maxColumns() value (older JEI), or a maxColumns field (JEI 15.59 keeps its values private)
                        if (v != null && (method(v.getClass(), "maxColumns") != null || cachedField(v.getClass(), "maxColumns") != null)) {
                            historyConfigField = f;
                            break;
                        }
                    } catch (ReflectiveOperationException | RuntimeException ignored) {}
                }
            }
            if (historyConfigField == null) throw new NoSuchFieldException("history grid config");
        }
        return historyConfigField.get(history);
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    static Rect2i rect(Object area) throws ReflectiveOperationException {
        return new Rect2i(num(area, "x", "getX"), num(area, "y", "getY"), num(area, "width", "getWidth"), num(area, "height", "getHeight"));
    }

    private static int num(Object o, String a, String b) throws ReflectiveOperationException {
        Method m;
        try {
            m = o.getClass()
                .getMethod(a);
        } catch (NoSuchMethodException e) {
            m = o.getClass()
                .getMethod(b);
        }
        m.setAccessible(true);
        return ((Number) m.invoke(o)).intValue();
    }

    private static void fail(String what, Throwable e) {
        NeiAddon.LOGGER.warn("JEI layout lookup '{}' failed; this part falls back to a fixed layout", what, e);
    }
}
