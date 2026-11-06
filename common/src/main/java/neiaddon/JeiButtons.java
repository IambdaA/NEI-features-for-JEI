package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;

/**
 * JEI draws two buttons in the bottom left corner of the screen: "show bookmarks" and "lookup history". JEI's own
 * bookmark list is replaced by this addon's panel, so the first button toggles that panel instead, and the history list
 * (drawn above the buttons on the left) gets room below the panel.
 *
 * <p>
 * JEI lays the buttons out like this (BookmarkOverlay): 6 px from the left and bottom edge, 20 px wide, 2 px apart. The
 * history list height and state are read from JEI by reflection; if that fails the history simply gets no reserved room.
 */
public final class JeiButtons {

    /** JEI's border margin and button size. */
    private static final int MARGIN = 6;
    private static final int SIZE = 20;
    private static final int GAP = 2;

    private static boolean failed;
    private static Method getClientConfigs, getClientConfig, historyEnabled, configGet;
    private static Field historyOverlayField;
    private static Method onThisSide, displayHeight;

    private JeiButtons() {}

    /**
     * The strip at the bottom that JEI's button row needs, always (the history list adds its own height on top, see
     * {@link #historyBand}). JEI moves its buttons to the right of any area an addon reserves in their row, so the panel
     * must stay above it, or the buttons leave the left corner and the "show bookmarks" button is no longer where it is read.
     */
    public static int rowBand() {
        return MARGIN + SIZE + 2;
    }

    // ---------------------------------------------------------------- where JEI really puts its buttons

    private static Object scannedFor;
    private static List<Object> found = List.of();
    private static long nextScan;
    private static boolean loggedButtons;

    /**
     * JEI's own buttons in the bottom left, left to right, as JEI has placed them (it moves them for areas other mods
     * reserve, and the position differs between versions). Found by reflection below the runtime (JEI's classes called
     * "...Button"); empty when that does not work, then the fixed layout above is used.
     */
    private static List<Rect2i> actual(int screenHeight) {
        Object runtime = Calculator.runtime;
        if (runtime == null) return List.of();
        long now = System.currentTimeMillis();
        if (scannedFor != runtime || found.isEmpty() && now >= nextScan) {
            scannedFor = runtime;
            nextScan = now + 2000;
            try {
                found = scan();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                found = List.of();
            }
        }
        int guiWidth = Minecraft.getInstance()
            .getWindow()
            .getGuiScaledWidth();
        List<Rect2i> out = new ArrayList<>();
        for (Object button : found) {
            try {
                Rect2i r;
                try {
                    r = JeiAreas.rect(button);
                } catch (ReflectiveOperationException e) {
                    r = JeiAreas.rect(button.getClass()
                        .getMethod("getArea")
                        .invoke(button));
                }
                if (r.getWidth() >= 16 && r.getWidth() <= 24 && r.getHeight() >= 16 && r.getHeight() <= 24
                    && r.getY() + r.getHeight() >= screenHeight - 40 && r.getX() < guiWidth / 2) out.add(r);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // a button that cannot be read is left out
            }
        }
        out.sort(Comparator.comparingInt(Rect2i::getX));
        if (!out.isEmpty() && !loggedButtons) {
            loggedButtons = true;
            StringBuilder sb = new StringBuilder();
            for (Rect2i r : out) sb.append(' ').append(r.getX()).append(',').append(r.getY());
            NeiAddon.LOGGER.info("JEI's buttons in the bottom left found at{} (class {})", sb, found.get(0).getClass().getName());
        }
        return out;
    }

    private static List<Object> scan() throws ReflectiveOperationException {
        List<Object> roots = new ArrayList<>();
        for (Object o : new Object[] { Calculator.runtime.getBookmarkOverlay(), Calculator.runtime.getIngredientListOverlay(),
            Calculator.runtime }) {
            if (o != null) roots.add(o);
        }
        List<Object> buttons = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        for (Object root : roots) {
            if (seen.add(root)) queue.add(new Object[] { root, 0 });
        }
        while (!queue.isEmpty()) {
            Object[] entry = queue.poll();
            Object o = entry[0];
            int depth = (Integer) entry[1];
            String name = o.getClass()
                .getName();
            if (name.startsWith("mezz.jei.") && o.getClass()
                .getSimpleName()
                .contains("Button")) {
                buttons.add(o);
                continue;
            }
            if (depth >= 5) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType()
                        .isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null) continue;
                        if (v instanceof Collection<?> col) {
                            int n = 0;
                            for (Object element : col) {
                                if (n++ == 60) break;
                                if (element != null && element.getClass()
                                    .getName()
                                    .startsWith("mezz.jei.") && seen.add(element)) queue.add(new Object[] { element, depth + 1 });
                            }
                        } else if (v.getClass()
                            .getName()
                            .startsWith("mezz.jei.") && seen.add(v)) {
                            queue.add(new Object[] { v, depth + 1 });
                        }
                    } catch (RuntimeException ignored) {}
                }
            }
        }
        return buttons;
    }

    /** Is the point over the "show bookmarks" button (JEI's first one)? */
    public static boolean overBookmarkButton(double mx, double my, int screenHeight) {
        List<Rect2i> real = actual(screenHeight);
        if (!real.isEmpty()) {
            Rect2i r = real.get(0);
            return inside(mx, my, r.getX(), r.getY(), r.getWidth(), r.getHeight());
        }
        return inside(mx, my, MARGIN, screenHeight - MARGIN - SIZE, SIZE, SIZE);
    }

    /** Left edge and top of this addon's own button: after JEI's last one in the row. */
    public static int configX() {
        Minecraft mc = Minecraft.getInstance();
        List<Rect2i> real = actual(mc.getWindow()
            .getGuiScaledHeight());
        if (!real.isEmpty()) {
            Rect2i last = real.get(real.size() - 1);
            return last.getX() + last.getWidth() + GAP;
        }
        return MARGIN + 2 * (SIZE + GAP);
    }

    public static int configY(int screenHeight) {
        List<Rect2i> real = actual(screenHeight);
        return real.isEmpty() ? screenHeight - MARGIN - SIZE : real.get(0)
            .getY();
    }

    public static int size() {
        return SIZE;
    }

    /** Is the point over any of the buttons (with a little slack)? */
    public static boolean overButtonRow(double mx, double my, int screenHeight) {
        List<Rect2i> real = actual(screenHeight);
        if (!real.isEmpty()) {
            Rect2i last = real.get(real.size() - 1);
            int x = real.get(0)
                .getX() - 2;
            int right = configX() + 4 * (SIZE + GAP);
            return inside(mx, my, x, last.getY() - 2, Math.max(right, last.getX() + last.getWidth()) - x, SIZE + 4);
        }
        return inside(mx, my, MARGIN - 2, screenHeight - MARGIN - SIZE - 2, SIZE * 4 + GAP * 3 + 4, SIZE + 4);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static Object clientConfig() throws ReflectiveOperationException {
        if (getClientConfigs == null) {
            Class<?> internal = Class.forName("mezz.jei.common.Internal");
            getClientConfigs = internal.getMethod("getClientConfigs");
            getClientConfigs.setAccessible(true);
        }
        Object configs = getClientConfigs.invoke(null);
        if (getClientConfig == null) {
            getClientConfig = configs.getClass()
                .getMethod("getClientConfig");
            getClientConfig.setAccessible(true);
        }
        return getClientConfig.invoke(configs);
    }

    private static Method searchBarPosition, searchBarGet;

    /** Is JEI's search bar set to "Bottom Center" (config: search / centerSearch)? False when unreadable. */
    public static boolean searchCentered() {
        try {
            Object config = clientConfig();
            if (searchBarPosition == null) {
                searchBarPosition = config.getClass()
                    .getMethod("searchBarPosition");
                searchBarPosition.setAccessible(true);
            }
            Object value = searchBarPosition.invoke(config);
            if (searchBarGet == null) {
                searchBarGet = value.getClass()
                    .getMethod("get");
                searchBarGet.setAccessible(true);
            }
            return "CENTERED".equals(String.valueOf(searchBarGet.invoke(value)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * How much of the bottom of the left side JEI's lookup history list needs (0 when it is off, on the other side, or
     * unreadable). Includes the button row below it.
     */
    public static int historyBand() {
        if (failed || Calculator.runtime == null) return 0;
        try {
            Object config = clientConfig();
            if (historyEnabled == null) {
                historyEnabled = config.getClass()
                    .getMethod("lookupHistoryEnabled");
                historyEnabled.setAccessible(true);
            }
            Object value = historyEnabled.invoke(config);
            if (configGet == null) {
                configGet = value.getClass()
                    .getMethod("get");
                configGet.setAccessible(true);
            }
            if (!Boolean.TRUE.equals(configGet.invoke(value))) return 0;

            Object overlay = Calculator.runtime.getBookmarkOverlay();
            if (historyOverlayField == null) {
                historyOverlayField = overlay.getClass()
                    .getDeclaredField("lookupHistoryOverlay");
                historyOverlayField.setAccessible(true);
            }
            Object history = historyOverlayField.get(overlay);
            if (onThisSide == null) {
                onThisSide = history.getClass()
                    .getMethod("isDisplayedOnThisSide");
                onThisSide.setAccessible(true);
                displayHeight = history.getClass()
                    .getMethod("getDisplayHeight");
                displayHeight.setAccessible(true);
            }
            if (!Boolean.TRUE.equals(onThisSide.invoke(history))) return 0;
            int height = (Integer) displayHeight.invoke(history);
            return height + MARGIN + SIZE + 2;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            failed = true;
            NeiAddon.LOGGER.warn("Could not read JEI's lookup history state; the history list gets no reserved room", e);
            return 0;
        }
    }
}
