package neiaddon.emi;

import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import neiaddon.EmiCompat;
import neiaddon.NeiAddon;

/**
 * Puts the crafting tree into EMI's screen history (EMI's Esc, E and back key walk that history), by reflection. It uses no
 * EMI types at all: EMI's classes are named by string, so this class loads without EMI and non-EMI code may call it directly.
 * {@link EmiCompat} is the usual entry point for the rest of the addon. Every method does nothing when EMI isn't installed,
 * and after its first failure it logs one warning and does nothing for the rest of the session.
 */
public final class EmiHistoryReturn {

    private static final String HISTORY = "dev.emi.emi.runtime.EmiHistory";
    private static final String RECIPE_SCREEN = "dev.emi.emi.screen.RecipeScreen";

    /** EMI's static {@code List<Screen>} of the history (EmiHistory.HISTORIES), or null until first used. */
    private static Field histories;
    /** EMI's public field {@code old} of its recipe screen (the screen the recipe screen goes back to), or null. */
    private static Field old;
    private static boolean putFailed;
    private static boolean removeFailed;

    private EmiHistoryReturn() {}

    /**
     * EMI's recipe screen {@code recipe} was opened from the tree's lookup: EMI's history then holds the one inventory the
     * recipe screen goes back to. When that is the only entry, it is replaced with {@code tree}, so Esc, E and EMI's back
     * key return to the tree. In any other shape nothing changes.
     */
    public static void putTreeBelow(Screen tree, Screen recipe) {
        if (putFailed || !EmiCompat.installed()) return;
        try {
            List<Object> history = history();
            Object back = old().get(recipe);
            if (history.size() == 1 && history.get(0) == back) history.set(0, tree);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            putFailed = true;
            NeiAddon.LOGGER.warn("Could not put the crafting tree into EMI's history; EMI's back key may skip the tree", e);
        }
    }

    /**
     * The tree is shown again: it leaves EMI's history with every entry after it (nothing when it isn't there), so the
     * recipe screen above it does not return into the tree a second time.
     */
    public static void removeTree(Screen tree) {
        if (removeFailed || !EmiCompat.installed()) return;
        try {
            List<Object> history = history();
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i) == tree) {
                    history.subList(i, history.size())
                        .clear();
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            removeFailed = true;
            NeiAddon.LOGGER.warn("Could not remove the crafting tree from EMI's history; Esc may return to the tree twice", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> history() throws ReflectiveOperationException {
        if (histories == null) {
            Field f = Class.forName(HISTORY)
                .getDeclaredField("HISTORIES");
            f.setAccessible(true);
            histories = f;
        }
        return (List<Object>) histories.get(null);
    }

    private static Field old() throws ReflectiveOperationException {
        if (old == null) {
            old = Class.forName(RECIPE_SCREEN)
                .getField("old");
        }
        return old;
    }
}
