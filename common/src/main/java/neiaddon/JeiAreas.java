package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.Rect2i;

/**
 * Where JEI's item list and lookup history really are (they can be dragged to resize), read from JEI by reflection:
 * {@code IngredientListOverlay.contents.getBackgroundArea()} and {@code lookupHistoryOverlay}. Returns null when JEI's
 * internals differ; callers then fall back to a fixed layout.
 */
public final class JeiAreas {

    // one flag per feature, so one broken lookup cannot switch the others off
    private static boolean failedList, failedHistory, failedMatch, failedHeight;
    private static Field contentsField, historyField;
    private static Method contentsArea, historyShown, historyArea;

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
            Object overlay = Calculator.runtime.getIngredientListOverlay();
            if (historyField == null) {
                historyField = field(overlay.getClass(), "lookupHistoryOverlay");
            }
            Object history = historyField.get(overlay);
            if (historyShown == null || historyArea == null) {
                historyShown = history.getClass()
                    .getMethod("isListDisplayed");
                historyShown.setAccessible(true);
                historyArea = history.getClass()
                    .getMethod("getBackgroundArea");
                historyArea.setAccessible(true);
            }
            if (!Boolean.TRUE.equals(historyShown.invoke(history))) return null;
            return rect(historyArea.invoke(history));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail("history", e);
            failedHistory = true;
            return null;
        }
    }

    private static Field historyConfigField;
    private static Method gridArea, maxColumns, valueGet, valueSet;
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
            if (history() == null) return;
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
            if (maxColumns == null) {
                maxColumns = config.getClass()
                    .getMethod("maxColumns");
                maxColumns.setAccessible(true);
            }
            Object value = maxColumns.invoke(config);
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
                        if (v != null && v.getClass()
                            .getMethod("maxColumns") != null) {
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

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }

    private static Rect2i rect(Object area) throws ReflectiveOperationException {
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
