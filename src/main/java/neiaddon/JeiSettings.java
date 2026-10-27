package neiaddon;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

/**
 * JEI only opens a settings screen when another mod provides one, so this lists JEI's own config values (read through
 * JEI's config manager API by reflection, so a JEI without it just leaves the button greyed out) and edits them:
 * click toggles a yes/no, click cycles a fixed list of choices (right click goes back), and any other value is typed
 * in and applied with Enter. Wheel scrolls. JEI saves its config file itself when a value is set.
 */
@EventBusSubscriber(modid = NeiAddon.MODID, value = Dist.CLIENT)
public final class JeiSettings extends Screen {

    private static final int ROW = 22;
    private static final int TOP = 36;
    private static final int BOTTOM = 36;
    private static final int VALUE_W = 150;

    /** One JEI config value, or a category header when {@code value} is null. */
    private static final class Row {

        final String header;
        final Object value;
        String edit;
        boolean bad;

        Row(String header, Object value) {
            this.header = header;
            this.value = value;
        }
    }

    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;
    private Row editing;

    JeiSettings(Screen parent) {
        super(Component.literal("JEI settings"));
        this.parent = parent;
        load();
    }

    /** Can JEI's config values be read? */
    public static boolean available() {
        return !new JeiSettings(null).rows.isEmpty();
    }

    private void load() {
        try {
            if (Calculator.runtime == null) return;
            Object manager = Calculator.runtime.getClass()
                .getMethod("getConfigManager")
                .invoke(Calculator.runtime);
            Collection<?> files = (Collection<?>) call(manager, "getConfigFiles");
            for (Object file : files) {
                for (Object category : (List<?>) call(file, "getCategories")) {
                    List<Row> values = new ArrayList<>();
                    for (Object value : (Collection<?>) call(category, "getConfigValues")) values.add(new Row(null, value));
                    if (values.isEmpty()) continue;
                    rows.add(new Row(String.valueOf(call(category, "getName")), null));
                    rows.addAll(values);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            NeiAddon.LOGGER.warn("Could not read JEI's config values", e);
            rows.clear();
        }
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        for (Method m : target.getClass()
            .getMethods()) {
            if (m.getName()
                .equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    // ---------------------------------------------------------------- value access

    private static String nameOf(Row r) throws ReflectiveOperationException {
        Object localized = call(r.value, "getLocalizedName");
        return localized instanceof Component c ? c.getString() : String.valueOf(call(r.value, "getName"));
    }

    private static String shown(Row r) {
        try {
            Object serializer = call(r.value, "getSerializer");
            return String.valueOf(call(serializer, "serialize", call(r.value, "getValue")));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    private static boolean apply(Row r, Object value) {
        try {
            call(r.value, "set", value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static List<Object> choices(Row r) {
        try {
            Object serializer = call(r.value, "getSerializer");
            Optional<?> all = (Optional<?>) call(serializer, "getAllValidValues");
            if (all.isEmpty()) return List.of();
            return new ArrayList<>((Collection<?>) all.get());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return List.of();
        }
    }

    private static void click(Row r, int button) {
        try {
            Object current = call(r.value, "getValue");
            if (current instanceof Boolean b) {
                apply(r, !b);
                return;
            }
            List<Object> all = choices(r);
            if (!all.isEmpty() && all.size() <= 64) {
                int i = all.indexOf(current);
                int next = (i + (button == 1 ? -1 : 1) + all.size()) % all.size();
                apply(r, all.get(next));
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {}
    }

    private static boolean typed(Row r) {
        try {
            Object current = call(r.value, "getValue");
            return !(current instanceof Boolean) && choices(r).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private void commit() {
        Row r = editing;
        editing = null;
        if (r == null || r.edit == null) return;
        try {
            Object serializer = call(r.value, "getSerializer");
            Object result = call(serializer, "deserialize", r.edit);
            Optional<?> parsed = (Optional<?>) call(result, "getResult");
            r.bad = parsed.isEmpty() || !Boolean.TRUE.equals(call(serializer, "isValid", parsed.get())) || !apply(r, parsed.get());
        } catch (ReflectiveOperationException | RuntimeException e) {
            r.bad = true;
        }
        r.edit = r.bad ? r.edit : null;
    }

    // ---------------------------------------------------------------- layout and drawing

    private int listHeight() {
        return height - TOP - BOTTOM;
    }

    private int maxScroll() {
        int over = rows.size() * ROW - listHeight();
        return over <= 0 ? 0 : (over + ROW - 1) / ROW * ROW;
    }

    private int left() {
        return Math.max(10, width / 2 - 230);
    }

    private int right() {
        return Math.min(width - 10, width / 2 + 230);
    }

    private int rowAt(double mx, double my) {
        if (my < TOP || my >= height - BOTTOM) return -1;
        int i = (int) (my - TOP + scroll) / ROW;
        return i >= 0 && i < rows.size() && TOP + (i + 1) * ROW - scroll <= height - BOTTOM ? i : -1;
    }

    private boolean overDone(double mx, double my) {
        int x = width / 2 - 100;
        return mx >= x && mx < x + 200 && my >= height - BOTTOM + 8 && my < height - BOTTOM + 28;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        Font font = Minecraft.getInstance().font;
        g.drawString(font, title, (width - font.width(title)) / 2, 14, 0xFFFFFFFF, true);
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        List<FormattedCharSequence> tip = null;
        for (int i = 0; i < rows.size(); i++) {
            int y = TOP + i * ROW - scroll;
            if (y < TOP || y + ROW > height - BOTTOM) continue;
            Row r = rows.get(i);
            if (r.value == null) {
                g.drawString(font, r.header, left(), y + 7, 0xFFFFFF55, true);
                continue;
            }
            boolean hover = rowAt(mouseX, mouseY) == i;
            try {
                String name = nameOf(r);
                g.drawString(font, font.plainSubstrByWidth(name, right() - left() - VALUE_W - 10), left() + 6, y + 7, 0xFFFFFFFF, true);
                if (hover && mouseX < right() - VALUE_W) {
                    Object description = call(r.value, "getLocalizedDescription");
                    if (description instanceof Component c) tip = new ArrayList<>(font.split(c, 260));
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {}
            int bx = right() - VALUE_W;
            String text = r == editing ? r.edit + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "")
                : r.edit != null ? r.edit : shown(r);
            BookmarkPanel.button(g, font, bx, y + 1, VALUE_W, ROW - 2, font.plainSubstrByWidth(text, VALUE_W - 6),
                hover && mouseX >= bx);
            if (r.bad) g.fill(bx, y + ROW - 3, bx + VALUE_W, y + ROW - 1, 0xFFFF5555);
        }
        BookmarkPanel.button(g, font, width / 2 - 100, height - BOTTOM + 8, 200, 20, "Done", overDone(mouseX, mouseY));
        if (tip != null) g.renderTooltip(font, tip, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(parent);
    }

    // ---------------------------------------------------------------- input (screen events, so no Screen method signatures)

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof JeiSettings s)) return;
        event.setCanceled(true);
        double mx = event.getMouseX();
        double my = event.getMouseY();
        if (s.overDone(mx, my)) {
            s.commit();
            s.onClose();
            return;
        }
        s.commit();
        int i = s.rowAt(mx, my);
        if (i < 0 || mx < s.right() - VALUE_W || mx >= s.right()) return;
        Row r = s.rows.get(i);
        if (r.value == null) return;
        if (typed(r)) {
            s.editing = r;
            r.edit = shown(r);
            r.bad = false;
        } else {
            click(r, event.getButton());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof JeiSettings s)) return;
        s.scroll -= (int) Math.signum(event.getScrollDeltaY()) * ROW;
        s.scroll = Math.max(0, Math.min(s.scroll, s.maxScroll()));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof JeiSettings s) || s.editing == null) return;
        event.setCanceled(true);
        int code = event.getKeyCode();
        Row r = s.editing;
        if (code == GLFW.GLFW_KEY_ENTER || code == GLFW.GLFW_KEY_KP_ENTER) {
            s.commit();
        } else if (code == GLFW.GLFW_KEY_ESCAPE) {
            r.edit = null;
            r.bad = false;
            s.editing = null;
        } else if (code == GLFW.GLFW_KEY_BACKSPACE && !r.edit.isEmpty()) {
            r.edit = r.edit.substring(0, r.edit.length() - 1);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onChar(ScreenEvent.CharacterTyped.Pre event) {
        if (!(event.getScreen() instanceof JeiSettings s) || s.editing == null) return;
        event.setCanceled(true);
        if (event.getCodePoint() >= ' ' && s.editing.edit.length() < 200) s.editing.edit += event.getCodePoint();
    }
}
