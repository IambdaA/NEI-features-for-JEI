package neiaddon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
import neiaddon.event.ScreenEvent;
import neiaddon.config.ConfigSpec;
import org.lwjgl.glfw.GLFW;

/**
 * This addon's settings, built like NEI's options screen: a title bar, a column of buttons that open sub pages
 * (Inventory, World, ...) and, on a page, the options as "label ... value" rows. A yes/no option toggles on click, a
 * choice shows its name (click cycles, right click goes back), a number is typed in (Enter applies) and a color opens
 * the color picker. The wheel scrolls. Mouse and keys come through screen events, like the other screens of this addon.
 */
public final class NeiSettings extends Screen {

    private static final int ROW = 20;
    private static final int TOP = 30;
    private static final int BOTTOM = 34;
    private static final int COLUMN = 300;
    private static final int VALUE_W = 100;

    private enum Kind {
        PAGE, ACTION, TOGGLE, NUMBER, CHOICE, COLOR
    }

    /** One line of a page. */
    private static final class Entry {

        final Kind kind;
        final String label;
        final String tip;
        final ConfigSpec.ConfigValue<?> value;
        final String[] names;
        final int min;
        final int max;
        final Page page;
        final Runnable action;
        String edit;

        Entry(Kind kind, String label, String tip, ConfigSpec.ConfigValue<?> value, String[] names, int min, int max, Page page,
            Runnable action) {
            this.kind = kind;
            this.label = label;
            this.tip = tip;
            this.value = value;
            this.names = names;
            this.min = min;
            this.max = max;
            this.page = page;
            this.action = action;
        }
    }

    private static final class Page {

        final String title;
        final List<Entry> entries = new ArrayList<>();

        Page(String title) {
            this.title = title;
        }

        Page sub(Page child) {
            entries.add(new Entry(Kind.PAGE, child.title, "", null, null, 0, 0, child, null));
            return this;
        }

        Page action(String label, Runnable run) {
            entries.add(new Entry(Kind.ACTION, label, "", null, null, 0, 0, null, run));
            return this;
        }

        Page toggle(String label, String tip, ConfigSpec.BooleanValue v) {
            entries.add(new Entry(Kind.TOGGLE, label, tip, v, null, 0, 0, null, null));
            return this;
        }

        Page number(String label, String tip, ConfigSpec.IntValue v, int min, int max) {
            entries.add(new Entry(Kind.NUMBER, label, tip, v, null, min, max, null, null));
            return this;
        }

        Page choice(String label, String tip, ConfigSpec.IntValue v, String... names) {
            entries.add(new Entry(Kind.CHOICE, label, tip, v, names, 0, names.length - 1, null, null));
            return this;
        }

        Page color(String label, String tip, ConfigSpec.IntValue v) {
            entries.add(new Entry(Kind.COLOR, label, tip, v, null, 0, 0xFFFFFF, null, null));
            return this;
        }
    }

    // ---------------------------------------------------------------- the pages (NEI's structure)

    private static Page root(Screen[] holder) {
        Page highlight = new Page("Highlight Tips")
            .toggle("Highlight Tips", "Show the name of the block under the crosshair. Toggle in game with Numpad 0.", AddonConfig.HUD_ENABLED)
            .number("Horizontal Position", "0 (left) to 10000 (right). Easier: use the Move Highlight Tooltips key.", AddonConfig.HUD_X, 0, 10000)
            .number("Vertical Position", "0 (top) to 10000 (bottom).", AddonConfig.HUD_Y, 0, 10000)
            .toggle("Show Mod Name", "Extra (not in NEI).", AddonConfig.HUD_SHOW_MOD)
            .toggle("Show Registry ID", "Extra (not in NEI).", AddonConfig.HUD_SHOW_ID);
        Page bookmarks = new Page("Bookmarks Panel")
            .choice("Recipe Tooltips", "When a bookmarked recipe shows its picture in the tooltip. Groups made from a recipe tree are todo-list view.",
                AddonConfig.BM_TOOLTIP_MODE, "Never", "Default View", "Todo List View", "Always")
            .choice("Show Recipe Marker", "How recipe results are marked.", AddonConfig.BM_MARKER_MODE, "First Result", "Every Result (R)", "Background")
            .color("Recipe Marker Color", "Color of the R and xN markers.", AddonConfig.BM_MARKER_COLOR)
            .toggle("Show Recipe Handler Icon", "Show the recipe type's icon instead of the R marker.", AddonConfig.BM_HANDLER_ICON)
            .toggle("World Specific Bookmarks", "Keep a bookmark list for every world and server.", AddonConfig.BM_WORLD_SPECIFIC)
            .toggle("Crafting Tree Zoom Without Ctrl", "Off: Ctrl + wheel zooms and the wheel scrolls.", AddonConfig.TREE_ZOOM_WITHOUT_CTRL);
        Page craftables = new Page("Craftables Panel")
            .toggle("Enabled", "What the open container can craft from your inventory, above the quantity field.", AddonConfig.CRAFTABLES_ENABLED)
            .number("Use Rows", "How many rows the panel shows (scroll for more).", AddonConfig.CRAFTABLES_ROWS, 1, 5)
            .toggle("Favorites Only", "Only list items whose favorite recipe can be crafted.", AddonConfig.CRAFTABLES_FAVORITES_ONLY);
        Page zoom = new Page("Item Zoom Panel")
            .toggle("Item Zoom Panel Visibility", "Draw the hovered item large beside the inventory.", AddonConfig.ZOOM_ENABLED)
            .toggle("Overlays Only", "Only zoom items of the JEI item list and the bookmark panel.", AddonConfig.ZOOM_OVERLAYS_ONLY)
            .toggle("Show Only Solid Items", "Only zoom blocks that are solid.", AddonConfig.ZOOM_ONLY_SOLID)
            .choice("Side", "Left and Right always use that side. Auto uses the side the mouse is not on.", AddonConfig.ZOOM_SIDE, "Auto", "Left", "Right")
            .toggle("Display Help Text", "Show \"Item Zoom\" and the key that toggles it below the item.", AddonConfig.ZOOM_HELP)
            .number("Zoom Amount", "Lower to make the zoom smaller (0 to 1000).", AddonConfig.ZOOM_AMOUNT, 0, 1000)
            .toggle("Display Name", "Show the item name below the zoomed item.", AddonConfig.ZOOM_NAME)
            .color("Name Color", "Color of the item name.", AddonConfig.ZOOM_NAME_COLOR);
        Page recipes = new Page("Recipe Screen")
            .toggle("Recipe Search Field", "A search field under JEI's recipe screen that filters the recipes shown. < ingredient, > result, ^ catalyst, | or, - not, @mod, #tooltip, $tag, &id.",
                AddonConfig.RECIPE_SEARCH);
        Page history = new Page("History Panel")
            .toggle("History As Wide As Item List", "Keeps JEI's lookup history (right side) as wide as the item list. Overwrites JEI's history column setting.",
                AddonConfig.HISTORY_MATCH_LIST);
        Page inventory = new Page("Inventory").sub(bookmarks)
            .sub(craftables)
            .sub(history)
            .sub(zoom)
            .sub(recipes)
            .toggle("Show Item Quantity Widget", "The quantity field above the JEI item list.", AddonConfig.QUANTITY_WIDGET)
            .toggle("Show Hotkeys Help Text", "Show \"Hold ALT for hotkeys\" in item tooltips. Alt still shows the keys.", AddonConfig.HOTKEYS_HELP)
            .toggle("Autocrafting", "Allow autocrafting of bookmarked recipes and craftables.", AddonConfig.AUTOCRAFTING)
            .number("Item Quantity", "Amount for \"with count\" actions and the Ctrl+Alt+scroll step. 0 = one stack.", AddonConfig.ITEM_QUANTITY, 0,
                Integer.MAX_VALUE);
        Page world = new Page("World").sub(highlight);
        return new Page("NEI Features Options").sub(inventory)
            .sub(world)
            .action("JEI Options", () -> {
                if (JeiSettings.available()) McCompat.setScreen(new JeiSettings(holder[0]));
            });
    }

    /** The settings screen, for the loader's mod list button. */
    public static Screen create(Screen parent) {
        Screen[] holder = new Screen[1];
        Page root = root(holder);
        NeiSettings screen = new NeiSettings(parent, root);
        holder[0] = screen;
        return screen;
    }

    public static void open(Screen parent) {
        McCompat.setScreen(create(parent));
    }

    // ---------------------------------------------------------------- the screen

    private final Screen parent;
    private final Page page;
    private int scroll;
    private Entry editing;

    private NeiSettings(Screen parent, Page page) {
        super(Component.literal(page.title));
        this.parent = parent;
        this.page = page;
    }

    private static String shown(Entry e) {
        Object v = e.value.get();
        if (v instanceof Boolean b) return b ? "Yes" : "No";
        if (e.names != null) return e.names[Math.max(0, Math.min((Integer) v, e.names.length - 1))];
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static void setInt(Entry e, int v) {
        ((ConfigSpec.ConfigValue<Integer>) e.value).set(Math.max(e.min, Math.min(e.max, v)));
        e.value.save();
    }

    @SuppressWarnings("unchecked")
    private void click(Entry e, int button) {
        switch (e.kind) {
            case PAGE -> McCompat.setScreen(new NeiSettings(this, e.page));
            case ACTION -> e.action.run();
            case TOGGLE -> {
                ((ConfigSpec.ConfigValue<Boolean>) e.value).set(!(Boolean) e.value.get());
                e.value.save();
            }
            case CHOICE -> {
                int n = e.names.length;
                setInt(e, ((Integer) e.value.get() + (button == 1 ? -1 : 1) + n) % n);
            }
            case COLOR -> {
                ConfigSpec.IntValue v = (ConfigSpec.IntValue) e.value;
                McCompat.setScreen(new ColorPickerScreen(this, Component.literal(e.label), v::get, c -> {
                    v.set(c);
                    v.save();
                }));
            }
            case NUMBER -> {
                editing = e;
                e.edit = String.valueOf(e.value.get());
            }
        }
    }

    private void commit() {
        Entry e = editing;
        editing = null;
        if (e == null || e.edit == null) return;
        try {
            setInt(e, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Long.parseLong(e.edit.isEmpty() ? "0" : e.edit))));
        } catch (NumberFormatException ignored) {}
        e.edit = null;
    }

    // ---------------------------------------------------------------- layout and drawing

    private int colLeft() {
        return (width - Math.min(COLUMN, width - 20)) / 2;
    }

    private int colWidth() {
        return Math.min(COLUMN, width - 20);
    }

    private int maxScroll() {
        int over = page.entries.size() * ROW + 6 - (height - TOP - BOTTOM);
        return over <= 0 ? 0 : (over + ROW - 1) / ROW * ROW;
    }

    private int rowAt(double my) {
        if (my < TOP || my >= height - BOTTOM) return -1;
        int i = (int) (my - TOP - 4 + scroll) / ROW;
        return i >= 0 && i < page.entries.size() && TOP + 4 + (i + 1) * ROW - scroll <= height - BOTTOM ? i : -1;
    }

    private boolean overBack(double mx, double my) {
        int x = width / 2 - 100;
        return mx >= x && mx < x + 200 && my >= height - BOTTOM + 7 && my < height - BOTTOM + 27;
    }

    /** NEI's dark slate button. */
    private static void slate(GuiGraphics g, Font font, int x, int y, int w, int h, String text, boolean over) {
        g.fill(x, y, x + w, y + h, 0xFF05080C);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, over ? 0xFF4A5868 : 0xFF2B3440);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, over ? 0xFF8093A8 : 0xFF566475);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2, over ? 0xFFFFFFA0 : 0xFFE0E0E0, true);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        Font font = Minecraft.getInstance().font;
        g.fill(0, TOP, width, height - BOTTOM, 0xB0000000);
        g.fill(0, 0, width, TOP, 0xFF3C3C3C);
        g.fill(0, height - BOTTOM, width, height, 0xFF3C3C3C);
        g.drawString(font, title, (width - font.width(title)) / 2, 11, 0xFFFFFFFF, true);
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        List<FormattedCharSequence> tip = null;
        int left = colLeft();
        int w = colWidth();
        for (int i = 0; i < page.entries.size(); i++) {
            int y = TOP + 4 + i * ROW - scroll;
            if (y < TOP || y + ROW > height - BOTTOM) continue;
            Entry e = page.entries.get(i);
            boolean hover = rowAt(mouseY) == i && mouseX >= left && mouseX < left + w;
            if (e.kind == Kind.PAGE || e.kind == Kind.ACTION) {
                slate(g, font, left, y, w, ROW - 2, e.label, hover);
                continue;
            }
            g.drawString(font, font.plainSubstrByWidth(e.label, w - VALUE_W - 10), left + 4, y + 6, 0xFFFFFFFF, true);
            if (hover && mouseX < left + w - VALUE_W && !e.tip.isEmpty()) tip = new ArrayList<>(font.split(Component.literal(e.tip), 260));
            int bx = left + w - VALUE_W;
            String text = e == editing ? e.edit + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "")
                : e.kind == Kind.COLOR ? String.format("#%06X", (Integer) e.value.get()) : shown(e);
            slate(g, font, bx, y, VALUE_W, ROW - 2, text, hover && mouseX >= bx);
            if (e.kind == Kind.COLOR) g.fill(bx + 4, y + 4, bx + 16, y + ROW - 6, 0xFF000000 | (Integer) e.value.get());
        }
        slate(g, font, width / 2 - 100, height - BOTTOM + 7, 200, 20, "Back", overBack(mouseX, mouseY));
        if (tip != null) g.renderTooltip(font, tip, mouseX, mouseY);
    }

    @Override
    public void onClose() {
        McCompat.setScreen(parent);
    }

    // ---------------------------------------------------------------- input

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s)) return;
        event.setCanceled(true);
        double mx = event.getMouseX();
        double my = event.getMouseY();
        s.commit();
        if (s.overBack(mx, my)) {
            s.onClose();
            return;
        }
        int i = s.rowAt(my);
        if (i < 0 || mx < s.colLeft() || mx >= s.colLeft() + s.colWidth()) return;
        Entry e = s.page.entries.get(i);
        boolean wide = e.kind == Kind.PAGE || e.kind == Kind.ACTION;
        if (wide || mx >= s.colLeft() + s.colWidth() - VALUE_W) s.click(e, event.getButton());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s)) return;
        s.scroll -= (int) Math.signum(event.getScrollDeltaY()) * ROW;
        s.scroll = Math.max(0, Math.min(s.scroll, s.maxScroll()));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s) || s.editing == null) return;
        event.setCanceled(true);
        int code = event.getKeyCode();
        Entry e = s.editing;
        if (code == GLFW.GLFW_KEY_ENTER || code == GLFW.GLFW_KEY_KP_ENTER) {
            s.commit();
        } else if (code == GLFW.GLFW_KEY_ESCAPE) {
            e.edit = null;
            s.editing = null;
        } else if (code == GLFW.GLFW_KEY_BACKSPACE && !e.edit.isEmpty()) {
            e.edit = e.edit.substring(0, e.edit.length() - 1);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onChar(ScreenEvent.CharacterTyped.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s) || s.editing == null) return;
        event.setCanceled(true);
        int c = event.getCodePoint();
        if (c >= '0' && c <= '9' && s.editing.edit.length() < 10) s.editing.edit += (char) c;
    }
}
