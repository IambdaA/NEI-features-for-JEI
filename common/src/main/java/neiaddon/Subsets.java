package neiaddon;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import neiaddon.platform.Platform;
import org.lwjgl.glfw.GLFW;

/**
 * NEI's subsets menu, opened with the S button beside the N button: every mod with items in JEI's list and every creative
 * tab. A click on one writes it into JEI's search ({@code @mod} or {@code "%tab"}, JEI's own search prefixes; JEI keeps
 * a phrase together only when the quote comes first), so nothing is hidden behind JEI's back and clearing the search
 * undoes it; "Clear search" does that from the menu. The mouse wheel
 * scrolls; Esc or a click outside closes it.
 */
public final class Subsets {

    private static final int LINE = 11;
    private static final int PAD = 4;
    private static final int WIDTH = 170;
    private static final int BACKGROUND = 0xF0100010;
    private static final int BORDER = 0xFF5000A0;
    private static final int HIGHLIGHT = 0x60FFFFFF;

    /** One line of the menu: a header (no filter) or an entry with the search text it sets. */
    private record Line(String label, String filter, boolean header) {}

    private static List<Line> lines = List.of();
    private static int x, bottom, scroll, hover = -1;

    private Subsets() {}

    static boolean open() {
        return !lines.isEmpty();
    }

    static void close() {
        lines = List.of();
        hover = -1;
    }

    /** Opens the menu with its bottom left corner at the point (above JEI's button row), or closes it. */
    static void toggle(Screen s, int left, int buttonTop) {
        if (open()) {
            close();
            return;
        }
        IJeiRuntime rt = Calculator.runtime;
        if (rt == null) return;
        List<Line> built = new ArrayList<>();
        built.add(new Line("Clear search", "", false));
        // mods, by name, with how many items each has in JEI's list
        Map<String, Integer> counts = new TreeMap<>();
        for (ItemStack stack : rt.getIngredientManager()
            .getAllIngredients(VanillaTypes.ITEM_STACK)) {
            counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem())
                .getNamespace(), 1, Integer::sum);
        }
        List<Line> mods = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            String name = Platform.get()
                .modDisplayName(e.getKey())
                .orElse(e.getKey());
            mods.add(new Line(name + " §7(" + e.getValue() + ")", "@" + e.getKey(), false));
        }
        mods.sort((a, b) -> a.label()
            .compareToIgnoreCase(b.label()));
        built.add(new Line("Mods", null, true));
        built.addAll(mods);
        built.add(new Line("Creative Tabs", null, true));
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
            String name = tab.getDisplayName()
                .getString();
            if (!name.isBlank()) built.add(new Line(name, "\"%" + name.replace("\"", "") + "\"", false));
        }
        lines = built;
        scroll = 0;
        hover = -1;
        x = Math.max(2, Math.min(left, s.width - WIDTH - 2));
        bottom = buttonTop - 2;
    }

    private static int visibleLines() {
        return Math.max(1, Math.min(lines.size(), (bottom - PAD * 2 - 2) / LINE));
    }

    private static int height() {
        return PAD * 2 + visibleLines() * LINE;
    }

    private static int top() {
        return bottom - height();
    }

    private static int indexAt(double mx, double my) {
        if (mx < x || mx >= x + WIDTH) return -1;
        int row = (int) ((my - top() - PAD) / LINE);
        if (my < top() + PAD || row < 0 || row >= visibleLines()) return -1;
        int index = scroll + row;
        return index < lines.size() ? index : -1;
    }

    private static boolean inside(double mx, double my) {
        return mx >= x && mx < x + WIDTH && my >= top() && my < bottom;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!open()) return;
        double mx = event.getMouseX();
        double my = event.getMouseY();
        if (!inside(mx, my)) {
            // a click elsewhere closes the menu; on the S button itself the button's own handler toggles it
            if (!ConfigButton.overSubsets(event.getScreen(), mx, my)) close();
            return;
        }
        event.setCanceled(true);
        int i = indexAt(mx, my);
        if (i < 0 || lines.get(i)
            .header() || event.getButton() != 0) return;
        IJeiRuntime rt = Calculator.runtime;
        if (rt != null) rt.getIngredientFilter()
            .setFilterText(lines.get(i)
                .filter());
        close();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!open() || !inside(event.getMouseX(), event.getMouseY())) return;
        int max = Math.max(0, lines.size() - visibleLines());
        scroll = Math.max(0, Math.min(max, scroll - 3 * (int) Math.signum(event.getScrollDelta())));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (open() && event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            close();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRender(ScreenEvent.Render.Post event) {
        if (!open()) return;
        hover = indexAt(event.getMouseX(), event.getMouseY());
        GuiGraphics g = event.getGuiGraphics();
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.identity();
        pose.translateZ(400f);
        try {
            draw(g);
        } finally {
            pose.popMatrix();
        }
    }

    private static void draw(GuiGraphics g) {
        Font font = Minecraft.getInstance().font;
        int top = top();
        g.fill(x - 1, top - 1, x + WIDTH + 1, bottom + 1, BORDER);
        g.fill(x, top, x + WIDTH, bottom, BACKGROUND);
        for (int row = 0; row < visibleLines(); row++) {
            int index = scroll + row;
            if (index >= lines.size()) break;
            Line line = lines.get(index);
            int ly = top + PAD + row * LINE;
            if (index == hover && !line.header()) g.fill(x + 1, ly - 1, x + WIDTH - 1, ly + LINE - 1, HIGHLIGHT);
            String text = font.plainSubstrByWidth(line.label(), WIDTH - PAD * 2 - (line.header() ? 0 : 6));
            g.drawString(font, line.header() ? "§e" + text : text, x + PAD + (line.header() ? 0 : 6), ly + 1, 0xFFE0E0E0, true);
        }
        // a scroll bar when not everything fits
        if (lines.size() > visibleLines()) {
            int track = bottom - top - 2;
            int thumb = Math.max(6, track * visibleLines() / lines.size());
            int at = top + 1 + (track - thumb) * scroll / Math.max(1, lines.size() - visibleLines());
            g.fill(x + WIDTH - 3, at, x + WIDTH - 1, at + thumb, 0xFFA0A0A0);
        }
    }
}
