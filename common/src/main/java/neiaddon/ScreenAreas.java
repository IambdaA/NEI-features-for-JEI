package neiaddon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;

/**
 * The buttons other mods add to the open screen (FTB's sidebar, Curios, inventory tweaks, and so on), read from the
 * screen's own widget list. This does not depend on the mod telling JEI about its area, so it catches the ones that
 * never do. Only the widgets that sit in the way of the bookmark panel matter; the caller filters by area.
 */
public final class ScreenAreas {

    private static String loggedFor = "";

    private ScreenAreas() {}

    /** Visible widgets of the screen that are not ours or JEI's, as rectangles. Empty when there are none. */
    public static List<Rect2i> widgets(Screen screen) {
        List<Rect2i> out = new ArrayList<>();
        if (screen == null) return out;
        try {
            collect(screen.children(), out, 0, screen.width, screen.height);
        } catch (RuntimeException | LinkageError e) {
            // a screen whose widget list cannot be read gives nothing; the JEI areas still apply
        }
        return out;
    }

    private static void collect(List<? extends GuiEventListener> children, List<Rect2i> out, int depth, int sw, int sh) {
        for (GuiEventListener child : children) {
            if (child instanceof AbstractWidget w) {
                String name = w.getClass()
                    .getName();
                if (name.startsWith("neiaddon.") || name.startsWith("mezz.jei.")) continue;
                if (!w.visible || w.getWidth() <= 0 || w.getHeight() <= 0) continue;
                // a widget that spans most of the screen is a background, not a button to keep clear of
                if (w.getWidth() > sw / 2 || w.getHeight() > sh * 3 / 4) continue;
                out.add(new Rect2i(w.getX(), w.getY(), w.getWidth(), w.getHeight()));
            } else if (child instanceof ContainerEventHandler group && depth < 2) {
                collect(group.children(), out, depth + 1, sw, sh);
            }
        }
    }

    /**
     * Joins rectangles that touch or lie within {@code gap} pixels of each other (a row of buttons becomes one box), so the
     * panel gives way once around the whole group and not button by button.
     */
    static List<Rect2i> merge(List<Rect2i> areas, int gap) {
        List<Rect2i> list = new ArrayList<>(areas);
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    Rect2i a = list.get(i);
                    Rect2i b = list.get(j);
                    if (a.getX() - gap > b.getX() + b.getWidth() || b.getX() - gap > a.getX() + a.getWidth()
                        || a.getY() - gap > b.getY() + b.getHeight() || b.getY() - gap > a.getY() + a.getHeight()) continue;
                    int x0 = Math.min(a.getX(), b.getX());
                    int y0 = Math.min(a.getY(), b.getY());
                    int x1 = Math.max(a.getX() + a.getWidth(), b.getX() + b.getWidth());
                    int y1 = Math.max(a.getY() + a.getHeight(), b.getY() + b.getHeight());
                    list.remove(j);
                    list.set(i, new Rect2i(x0, y0, x1 - x0, y1 - y0));
                    changed = true;
                    break outer;
                }
            }
        }
        return list;
    }

    /** Writes what the panel keeps clear of to the log, once for each kind of screen, to make a report easy to read. */
    static void log(Screen screen, List<Rect2i> jei, List<Rect2i> widgets) {
        String key = screen.getClass()
            .getName() + jei.size() + "/" + widgets.size();
        if (key.equals(loggedFor)) return;
        loggedFor = key;
        NeiAddon.LOGGER.info("Bookmark panel on {}: {} area(s) from JEI, {} widget(s) from the screen {}", screen.getClass()
            .getSimpleName(), jei.size(), widgets.size(), describe(widgets));
    }

    private static String describe(List<Rect2i> widgets) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Rect2i r : widgets) {
            if (n++ == 8) {
                sb.append(" ...");
                break;
            }
            sb.append(sb.length() == 0 ? "[" : " ")
                .append(r.getX())
                .append(',')
                .append(r.getY())
                .append(' ')
                .append(r.getWidth())
                .append('x')
                .append(r.getHeight());
        }
        return sb.length() == 0 ? "" : sb.append(']')
            .toString();
    }
}
