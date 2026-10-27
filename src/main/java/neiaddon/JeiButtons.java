package neiaddon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    /** Is the point over the "show bookmarks" button? */
    public static boolean overBookmarkButton(double mx, double my, int screenHeight) {
        return inside(mx, my, MARGIN, screenHeight - MARGIN - SIZE, SIZE, SIZE);
    }

    /** Left edge and top of this addon's own button, the third one in the row. */
    public static int configX() {
        return MARGIN + 2 * (SIZE + GAP);
    }

    public static int configY(int screenHeight) {
        return screenHeight - MARGIN - SIZE;
    }

    public static int size() {
        return SIZE;
    }

    /** Is the point over any of the buttons (with a little slack)? */
    public static boolean overButtonRow(double mx, double my, int screenHeight) {
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
