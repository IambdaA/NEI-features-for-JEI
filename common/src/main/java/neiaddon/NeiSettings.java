package neiaddon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
<<<<<<< Updated upstream
import net.minecraft.network.chat.Component;
=======
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
>>>>>>> Stashed changes
import net.minecraft.util.FormattedCharSequence;
import neiaddon.event.EventPriority;
import neiaddon.event.SubscribeEvent;
<<<<<<< Updated upstream
import neiaddon.event.ScreenEvent;
import neiaddon.config.ConfigSpec;
=======
import org.joml.Matrix3x2fStack;
>>>>>>> Stashed changes
import org.lwjgl.glfw.GLFW;

/**
 * This addon's settings, built like NEI's options screen: a title bar, a column of buttons that open sub pages
 * (Inventory, World, ...) and, on a page, the options as "label ... value" rows. A yes/no option toggles on click, a
 * choice shows its name (click cycles, right click goes back), a number is typed in (Enter applies) and a color opens
 * the color picker. The wheel scrolls. Mouse and keys come through screen events, like the other screens of this addon.
 */
public final class NeiSettings extends Screen {

<<<<<<< Updated upstream
    private static final int ROW = 20;
    private static final int TOP = 30;
    private static final int BOTTOM = 34;
    private static final int COLUMN = 300;
    private static final int VALUE_W = 100;
=======
    private static final int LIST_TOP = 20;
    private static final int ROW_H = 24;
    private static final int BTN_H = 20;
    private static final int PREFIX_X = 10;
    private static final int MAX_SLOT_W = 320;
    private static final int LEFT_PAD = 24;
    private static final int RIGHT_PAD = 20;
    private static final int BAR_W = 6;
    private static final int MAX_FIELD_W = 100;
    private static final int ICON = 24;
    private static final int SHEET = 512;

    private static final Identifier SPRITES = Identifier.fromNamespaceAndPath(NeiAddon.MODID, "textures/nei_sprites.png");
    private static final Identifier BTN = Identifier.parse("minecraft:widget/button");
    private static final Identifier BTN_HOVER = Identifier.parse("minecraft:widget/button_highlighted");
    private static final Identifier BTN_OFF = Identifier.parse("minecraft:widget/button_disabled");
    private static final Identifier TILE = Identifier.parse("minecraft:textures/gui/menu_background.png");
>>>>>>> Stashed changes

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
            .toggle("Compact Fluid Amounts", "Short fluid amounts in slots: 500mB, then buckets from 1000 mB (2.5B). Off: millibuckets up to 9999 (2500mB), which can run into the next slot.",
                AddonConfig.FLUID_COMPACT)
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
                AddonConfig.RECIPE_SEARCH)
            .toggle("Scroll To Pick Favorite Result", "Mouse wheel over a recipe's heart picks which of its results to favorite. Off: the heart uses the first result; Shift+F over a result slot favorites that one.",
                AddonConfig.HEART_SCROLL_RESULT);
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

<<<<<<< Updated upstream
    /** NEI's dark slate button. */
    private static void slate(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String text, boolean over) {
        g.fill(x, y, x + w, y + h, 0xFF05080C);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, over ? 0xFF4A5868 : 0xFF2B3440);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, over ? 0xFF8093A8 : 0xFF566475);
        g.text(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2, over ? 0xFFFFFFA0 : 0xFFE0E0E0, true);
=======
    /** Where an entry's button (or text field, or icon row) is, across the page's column. */
    private Box box(Entry e, Font font) {
        int cx = contentX();
        int cw = contentW();
        switch (e.kind) {
            case TOGGLE, CHOICE -> {
                if (!e.prefixed) return new Box(cx, cw);
                int w = Math.max(60, font.width(shown(e)) + 8);
                return new Box(cx + cw - w, w);
            }
            case NUMBER, COLOR -> {
                int prefixW = font.width(e.label) + PREFIX_X;
                int w = Math.max(60, Math.min(MAX_FIELD_W, cw - prefixW) - 16);
                return new Box(cx + cw - w, w);
            }
            case ICONS -> {
                int w = 24 * e.icons.size() - 4;
                return new Box(cx + cw - w, w);
            }
            default -> {
                return new Box(cx, cw);
            }
        }
    }

    private boolean tileThere() {
        if (tileThere == null) {
            try {
                tileThere = Minecraft.getInstance()
                    .getResourceManager()
                    .getResource(TILE)
                    .isPresent();
            } catch (RuntimeException ex) {
                tileThere = false;
            }
        }
        return tileThere;
    }

    // ---------------------------------------------------------------- drawing

    private void tiles(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (h <= 0) return;
        if (tileThere()) g.blit(RenderPipelines.GUI_TEXTURED, TILE, x, y, 0f, 0f, w, h, 32, 32);
        else g.fill(x, y, x + w, y + h, 0xFF2A2A2A);
    }

    /** NEI's button: type 0 is the dark disabled look (an icon that is off), 1 normal, 2 hovered. */
    private static void button(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, String label, int type) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, type == 0 ? BTN_OFF : type == 2 ? BTN_HOVER : BTN, x, y, w, h);
        if (label == null || label.isEmpty()) return;
        String t = font.width(label) > w - 4 ? font.plainSubstrByWidth(label, w - 4) : label;
        g.text(font, t, x + (w - font.width(t)) / 2, y + (h - 8) / 2, type == 0 ? 0xFFA0A0A0 : type == 2 ? 0xFFFFFFA0 : 0xFFE0E0E0, true);
    }

    private static void icon(GuiGraphicsExtractor g, int u, int v, int x, int y) {
        Matrix3x2fStack pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(0.5f, 0.5f);
        g.blit(RenderPipelines.GUI_TEXTURED, SPRITES, 0, 0, (float) u, (float) v, ICON, ICON, SHEET, SHEET);
        pose.popMatrix();
    }

    private void field(GuiGraphicsExtractor g, Font font, int x, int y, int w, Entry e) {
        boolean focused = focus == e;
        String t = focused ? text : display(e);
        int pad = e.kind == Kind.COLOR ? 20 : 4;
        g.fill(x, y, x + w, y + BTN_H, focused ? 0xFFFFFFFF : 0xFFA0A0A0);
        g.fill(x + 1, y + 1, x + w - 1, y + BTN_H - 1, 0xFF000000);
        if (e.kind == Kind.COLOR) {
            g.fill(x + 4, y + 4, x + 16, y + BTN_H - 4, 0xFFFFFFFF);
            g.fill(x + 5, y + 5, x + 15, y + BTN_H - 5, 0xFF000000 | (Integer) e.value.get());
        }
        int inner = w - pad - 4;
        boolean cut = font.width(t) > inner;
        String visible = cut ? font.plainSubstrByWidth(t, inner, true) : t;
        g.text(font, visible, x + pad, y + 6, 0xFFE0E0E0, true);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = x + pad + (cut ? font.width(visible) : font.width(t.substring(0, Math.min(caret, t.length()))));
            g.fill(cx, y + 5, cx + 1, y + 15, 0xFFD0D0D0);
        }
    }

    private void fade(GuiGraphicsExtractor g, int y, boolean down) {
        for (int k = 0; k < 4; k++) {
            int alpha = 255 - (int) (255 * (k + 0.5) / 4);
            int row = down ? y + k : y - 1 - k;
            g.fill(0, row, width, row + 1, alpha << 24);
        }
>>>>>>> Stashed changes
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        Font font = Minecraft.getInstance().font;
        g.fill(0, TOP, width, height - BOTTOM, 0xB0000000);
        g.fill(0, 0, width, TOP, 0xFF3C3C3C);
        g.fill(0, height - BOTTOM, width, height, 0xFF3C3C3C);
        g.text(font, title, (width - font.width(title)) / 2, 11, 0xFFFFFFFF, true);
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
<<<<<<< Updated upstream
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
            g.text(font, font.plainSubstrByWidth(e.label, w - VALUE_W - 10), left + 4, y + 6, 0xFFFFFFFF, true);
            if (hover && mouseX < left + w - VALUE_W && !e.tip.isEmpty()) tip = new ArrayList<>(font.split(Component.literal(e.tip), 260));
            int bx = left + w - VALUE_W;
            String text = e == editing ? e.edit + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "")
                : e.kind == Kind.COLOR ? String.format("#%06X", (Integer) e.value.get()) : shown(e);
            slate(g, font, bx, y, VALUE_W, ROW - 2, text, hover && mouseX >= bx);
            if (e.kind == Kind.COLOR) g.fill(bx + 4, y + 4, bx + 16, y + ROW - 6, 0xFF000000 | (Integer) e.value.get());
=======
        int cx = contentX();
        int cw = contentW();
        int lb = listBottom();
        List<String> tip = new ArrayList<>();

        g.fill(barX(), LIST_TOP, barX() + BAR_W, lb, 0xFF000000);
        g.enableScissor(slotX(), LIST_TOP, slotX() + slotW(), lb);
        try {
            for (int i = 0; i < page.entries.size(); i++) {
                int y = rowY(i);
                if (y + BTN_H <= LIST_TOP || y >= lb) continue;
                Entry e = page.entries.get(i);
                Box b = box(e, font);
                boolean inRow = mouseY >= y && mouseY < y + BTN_H && mouseY >= LIST_TOP && mouseY < lb && mouseX >= cx && mouseX < cx + cw;
                boolean overBox = inRow && mouseX >= b.x() && mouseX < b.x() + b.w();
                if (e.prefixed) {
                    int avail = Math.max(0, b.x() - cx - PREFIX_X - 4);
                    g.text(font, font.plainSubstrByWidth(e.label, avail), cx + PREFIX_X, y + 6, 0xFFFFFFFF, true);
                }
                switch (e.kind) {
                    case PAGE, ACTION -> button(g, font, b.x(), y, b.w(), BTN_H, e.label, overBox ? 2 : 1);
                    case TOGGLE, CHOICE -> button(g, font, b.x(), y, b.w(), BTN_H, shown(e), overBox ? 2 : 1);
                    case NUMBER, COLOR -> field(g, font, b.x(), y, b.w(), e);
                    case ICONS -> {
                        for (int k = 0; k < e.icons.size(); k++) {
                            Icon ic = e.icons.get(k);
                            int ix = b.x() + 24 * k;
                            boolean on = ic.value().get();
                            button(g, font, ix, y, BTN_H, BTN_H, "", on ? 1 : 0);
                            icon(g, ic.u(), ic.v(), ix + 4, y + 4);
                            if (inRow && mouseX >= ix && mouseX < ix + BTN_H) {
                                tip.add(ic.name());
                                tip.add(on ? "On. Click to turn off." : "Off. Click to turn on.");
                            }
                        }
                    }
                }
                if (inRow && tip.isEmpty() && !e.tip.isEmpty() && (e.kind != Kind.ICONS || mouseX < b.x())) tip.add(e.tip);
            }
        } finally {
            g.disableScissor();
        }

        // the tile above and below the list, with a short fade into the list
        tiles(g, 0, 0, width, LIST_TOP);
        tiles(g, 0, lb, width, height - lb);
        fade(g, LIST_TOP, true);
        fade(g, lb, false);

        if (maxScroll() > 0) {
            int ty = thumbY();
            int th = thumbH();
            g.fill(barX(), ty, barX() + BAR_W, ty + th, 0xFF808080);
            g.fill(barX(), ty, barX() + BAR_W - 1, ty + th - 1, 0xFFC0C0C0);
        }
        g.text(font, title, (width - font.width(title)) / 2, 6, 0xFFFFFFFF, true);
        button(g, font, (width - backW()) / 2, height - 25, backW(), BTN_H, "Back", overBack(mouseX, mouseY) ? 2 : 1);

        if (!tip.isEmpty()) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (String s : tip) lines.addAll(font.split(Component.literal(s), 260));
            g.setTooltipForNextFrame(font, lines, mouseX, mouseY);
>>>>>>> Stashed changes
        }
        slate(g, font, width / 2 - 100, height - BOTTOM + 7, 200, 20, "Back", overBack(mouseX, mouseY));
        if (tip != null) g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
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
<<<<<<< Updated upstream
        s.scroll -= (int) Math.signum(event.getScrollDeltaY()) * ROW;
=======
        s.scroll -= (int) Math.signum(event.getScrollDeltaY()) * ROW_H;
>>>>>>> Stashed changes
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
