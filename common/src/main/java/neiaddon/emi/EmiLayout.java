package neiaddon.emi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.renderer.Rect2i;
import neiaddon.EmiCompat;

/**
 * Where EMI has put its item list, search box and bottom-left buttons, read from EMI's screen manager
 * ({@code dev.emi.emi.screen.EmiScreenManager}, internal, by reflection; EMI's own names are the same on every loader).
 * The search box and the buttons are vanilla widgets, so their places are read through the game's own widget class.
 * Constructing it fails when EMI's internals are not as expected; the plugin then leaves the addon's widgets where they
 * are without EMI.
 */
final class EmiLayout implements EmiCompat.LayoutHooks {

    private static final String MANAGER = "dev.emi.emi.screen.EmiScreenManager";
    /** EMI's slot: a 16 pixel item with 1 pixel of padding on each side (EmiScreenManager.ENTRY_SIZE). */
    private static final int ENTRY = 18;

    private final Method searchPanel;
    private final Method visible;
    private final Field space;
    private final Field tx, ty, tw, th;
    private final Field search, emi, tree;

    EmiLayout() throws ReflectiveOperationException {
        Class<?> manager = Class.forName(MANAGER);
        searchPanel = manager.getMethod("getSearchPanel");
        Class<?> panel = searchPanel.getReturnType();
        visible = panel.getMethod("isVisible");
        space = panel.getField("space");
        Class<?> screenSpace = space.getType();
        tx = screenSpace.getField("tx");
        ty = screenSpace.getField("ty");
        tw = screenSpace.getField("tw");
        th = screenSpace.getField("th");
        search = manager.getField("search");
        emi = manager.getField("emi");
        tree = manager.getField("tree");
        for (Field f : new Field[] { search, emi, tree }) {
            if (!AbstractWidget.class.isAssignableFrom(f.getType())) throw new NoSuchFieldException(f.getName() + " is not a widget");
        }
    }

    @Override
    public Rect2i index() {
        try {
            Object panel = searchPanel.invoke(null);
            if (panel == null || !(Boolean) visible.invoke(panel)) return null;
            Object s = space.get(panel);
            if (s == null) return null;
            int w = tw.getInt(s);
            int h = th.getInt(s);
            if (w <= 0 || h <= 0) return null;
            return new Rect2i(tx.getInt(s), ty.getInt(s), w * ENTRY, h * ENTRY);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Rect2i search() {
        return area(search);
    }

    @Override
    public List<Rect2i> buttons() {
        List<Rect2i> out = new ArrayList<>(2);
        for (Field f : new Field[] { emi, tree }) {
            Rect2i r = area(f);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** A widget kept in one of EMI's static fields, or null when it is missing or hidden. */
    private static Rect2i area(Field f) {
        try {
            AbstractWidget w = (AbstractWidget) f.get(null);
            if (w == null || !w.visible || w.getWidth() <= 0 || w.getHeight() <= 0) return null;
            return new Rect2i(w.getX(), w.getY(), w.getWidth(), w.getHeight());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
