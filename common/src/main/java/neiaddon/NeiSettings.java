package neiaddon;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.controls.KeyBindsScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import neiaddon.config.ConfigSpec;
import neiaddon.event.EventPriority;
import neiaddon.event.ScreenEvent;
import neiaddon.event.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * This addon's settings, drawn and laid out like NEI's options screen ({@code GuiOptionList}): a column of at most 320
 * pixels with a thin black scroll bar on its right, the dirt-like tile above and below it (with a short fade into the
 * list), the page's name centered at the top and a Back button at the bottom. A page lists its options as:
 * <ul>
 * <li>a full-width button that opens a sub page,</li>
 * <li>a full-width button that cycles a choice (Cheat Mode),</li>
 * <li>the option's name on the left and its value as a button on the right (Yes / No, Enabled / Disabled, a name),</li>
 * <li>the option's name on the left and a text field on the right for a number or a color,</li>
 * <li>the option's name on the left and a row of 20 x 20 icon buttons (Utilities, Gamemodes): a lit button is on, left
 * click turns one on and right click off.</li>
 * </ul>
 * The mouse and keys come through screen events, like the other screens of this addon. The screen does not pause the
 * game, and Esc goes back to the screen the settings were opened from.
 *
 * <p>
 * Ported from GTNH NotEnoughItems (LGPL-3.0-or-later).
 */
public final class NeiSettings extends Screen {

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

    private static final ResourceLocation SPRITES = new ResourceLocation(NeiAddon.MODID, "textures/nei_sprites.png");
    private static final ResourceLocation WIDGETS = new ResourceLocation("minecraft:textures/gui/widgets.png");
    private static final ResourceLocation TILE = new ResourceLocation("minecraft:textures/gui/options_background.png");

    private enum Kind {
        PAGE, ACTION, TOGGLE, NUMBER, CHOICE, COLOR, ICONS
    }

    /** One icon button of an icon row: its name (the tooltip), the yes/no it sets and where its picture is on the sheet. */
    private record Icon(String name, ConfigSpec.BooleanValue value, int u, int v) {}

    private record Box(int x, int w) {}

    /** One line of a page. */
    private static final class Entry {

        final Kind kind;
        final String label;
        final String tip;
        ConfigSpec.ConfigValue<?> value;
        String[] names;
        int min;
        int max;
        Page page;
        Runnable action;
        boolean prefixed;
        List<Icon> icons;

        Entry(Kind kind, String label, String tip) {
            this.kind = kind;
            this.label = label == null ? "" : label;
            this.tip = tip == null ? "" : tip;
        }
    }

    private static final class Page {

        final String title;
        final List<Entry> entries = new ArrayList<>();

        Page(String title) {
            this.title = title;
        }

        private Entry add(Kind kind, String label, String tip) {
            Entry e = new Entry(kind, label, tip);
            entries.add(e);
            return e;
        }

        Page sub(Page child) {
            add(Kind.PAGE, child.title, "").page = child;
            return this;
        }

        Page action(String label, String tip, Runnable run) {
            add(Kind.ACTION, label, tip).action = run;
            return this;
        }

        Page toggle(String label, String tip, ConfigSpec.BooleanValue v, String off, String on) {
            Entry e = add(Kind.TOGGLE, label, tip);
            e.value = v;
            e.names = new String[] { off, on };
            e.prefixed = true;
            return this;
        }

        Page toggle(String label, String tip, ConfigSpec.BooleanValue v) {
            return toggle(label, tip, v, "No", "Yes");
        }

        Page enabled(String label, String tip, ConfigSpec.BooleanValue v) {
            return toggle(label, tip, v, "Disabled", "Enabled");
        }

        Page number(String label, String tip, ConfigSpec.IntValue v, int min, int max) {
            Entry e = add(Kind.NUMBER, label, tip);
            e.value = v;
            e.min = min;
            e.max = max;
            e.prefixed = true;
            return this;
        }

        /** A choice with its name on the left, the chosen value as a button on the right. */
        Page choice(String label, String tip, ConfigSpec.IntValue v, String... names) {
            Entry e = add(Kind.CHOICE, label, tip);
            e.value = v;
            e.names = names;
            e.min = 0;
            e.max = names.length - 1;
            e.prefixed = true;
            return this;
        }

        /** A choice that is one full-width button showing the chosen value. */
        Page cycle(String tip, ConfigSpec.IntValue v, String... names) {
            Entry e = add(Kind.CHOICE, "", tip);
            e.value = v;
            e.names = names;
            e.min = 0;
            e.max = names.length - 1;
            return this;
        }

        Page color(String label, String tip, ConfigSpec.IntValue v) {
            Entry e = add(Kind.COLOR, label, tip);
            e.value = v;
            e.min = 0;
            e.max = 0xFFFFFF;
            e.prefixed = true;
            return this;
        }

        Page icons(String label, String tip, Icon... icons) {
            Entry e = add(Kind.ICONS, label, tip);
            e.icons = List.of(icons);
            e.prefixed = true;
            return this;
        }
    }

    // ---------------------------------------------------------------- the pages (NEI's structure)

    private static Page root(Screen[] holder) {
        Page highlight = new Page("Highlight Tooltips")
            .toggle("Highlight Tooltips", "Show the name of the block under the crosshair. Toggle in game with Numpad 0.", AddonConfig.HUD_ENABLED, "Hidden", "Shown")
            .number("Horizontal Position", "0 (left) to 10000 (right). Easier: use the Move Highlight Tooltips key.", AddonConfig.HUD_X, 0, 10000)
            .number("Vertical Position", "0 (top) to 10000 (bottom).", AddonConfig.HUD_Y, 0, 10000)
            .toggle("Show Mod Name", "Extra (not in NEI).", AddonConfig.HUD_SHOW_MOD)
            .toggle("Show Registry ID", "Extra (not in NEI).", AddonConfig.HUD_SHOW_ID);
        Page bookmarks = new Page("Bookmarks Panel")
            .toggle("Bookmarks Panel Visibility", "Show the bookmark panel. The bookmarks key and the bookmarks button in JEI's button row switch it too.", AddonConfig.BM_VISIBLE,
                "Hidden", "Visible")
            .toggle("World Specific", "Keep a bookmark list for every world and server.", AddonConfig.BM_WORLD_SPECIFIC)
            .toggle("REI Style Animation in Bookmarks", "Slide bookmarks to their new place when the panel rearranges.", AddonConfig.BM_ANIMATION)
            .choice("Show Recipe Tooltips in Bookmarks", "When a bookmarked recipe shows its picture in the tooltip. Horizontal view is a group's default view, vertical view is the todo-list view (groups made from a recipe tree).",
                AddonConfig.BM_TOOLTIP_MODE, "None", "In Horizontal View", "In Vertical View", "Always")
            .choice("Show Recipe Marker", "How recipe results are marked: the first result only, every result with an R, or a tinted background.", AddonConfig.BM_MARKER_MODE, "First Result", "Every Result (R)", "Background")
            .number("Chain Tooltip Rows", "Most rows of icons in a group's crafting chain tooltip. 0 = no limit. Extra (not in NEI: its Max Vertical Tooltip Rows is for named lists this addon does not have).", AddonConfig.BM_TIP_ROWS, 0, 50)
            .toggle("JEI's Bookmark Button Fills This Panel", "What JEI's own bookmark button (on each recipe) and bookmark key add goes into this panel instead of JEI's own list; a recipe shows as its result with the recipe type's icon (hold Shift for the recipe), an item as a plain bookmark. Extra (not in NEI).", AddonConfig.BM_JEI_BOOKMARKS)
            .number("Panel Margin Top", "Extra space above the bookmark panel in pixels. The panel already moves out of the way of other mods' buttons and areas it can find; use this for one that draws on the screen without telling anyone. Extra (not in NEI).", AddonConfig.BM_MARGIN_TOP, 0, 400)
            .number("Panel Margin Left", "Extra space left of the bookmark panel in pixels. Extra (not in NEI).", AddonConfig.BM_MARGIN_LEFT, 0, 400)
                        .color("Recipe Marker Color", "Color of the R and xN markers.", AddonConfig.BM_MARKER_COLOR)
            .toggle("Show Handler Icon", "Show the recipe type's icon instead of the R marker.", AddonConfig.BM_HANDLER_ICON)
            .toggle("Bookmark Items With Recipe", "Pressing the bookmarks key over an item inside a recipe of JEI's recipe screen saves that recipe with the item; its tooltip then shows the recipe.", AddonConfig.BM_ITEMS_WITH_RECIPE)
            .toggle("Bookmark Recipe With Count", "A recipe saved with Shift + the bookmarks key starts with a count of one run. Off: it starts without a count (Ctrl + Shift saves it with one).", AddonConfig.BM_RECIPE_WITH_COUNT)
            .toggle("Tree Zoom without Ctrl", "Zoom the crafting tree with the plain wheel. Off: Ctrl + wheel zooms the crafting tree and the wheel scrolls it.", AddonConfig.TREE_ZOOM_WITHOUT_CTRL)
            .toggle("Compact Fluid Amounts", "Short fluid amounts in slots: 500mB, then buckets from 1000 mB (2.5B). Off: millibuckets up to 9999 (2500mB), which can run into the next slot. Extra (not in NEI).",
                AddonConfig.FLUID_COMPACT);
        Page craftables = new Page("Craftables Panel")
            .enabled("Craftables Panel Visibility", "What the open container can craft from your inventory, above the quantity field.", AddonConfig.CRAFTABLES_ENABLED)
            .number("Rows", "How many rows the panel shows (scroll for more).", AddonConfig.CRAFTABLES_ROWS, 1, 5)
            .toggle("Show Favorites Only", "Only list items whose favorite recipe can be crafted.", AddonConfig.CRAFTABLES_FAVORITES_ONLY);
        Page zoom = new Page("Item Zoom Panel")
            .enabled("Item Zoom Panel Visibility", "Draw the hovered item large beside the inventory.", AddonConfig.ZOOM_ENABLED)
            .enabled("NEI Overlays Only", "Zoom items only from the JEI ingredient and bookmark list overlays.", AddonConfig.ZOOM_OVERLAYS_ONLY)
            .enabled("Show Only Solid Items", "Only zoom blocks that are solid.", AddonConfig.ZOOM_ONLY_SOLID)
            .choice("Preferred Side", "Auto prefers the side whose panel holds fewer items. Left and Right always prefer that side. The zoom moves to the other side when the cursor is over the preferred one.", AddonConfig.ZOOM_SIDE, "Auto", "Left", "Right")
            .enabled("Display Help Text", "Display name \"Item Zoom\" and the hotkey to toggle it below the zoomed item.", AddonConfig.ZOOM_HELP)
            .number("Zoom Amount", "Set lower amount to make the item zoom less (0 to 1000).", AddonConfig.ZOOM_AMOUNT, 0, 1000)
            .toggle("Display Name", "Show the item name below the zoomed item.", AddonConfig.ZOOM_NAME, "Hide", "Show")
            .color("Display Name Color", "Color of the item name.", AddonConfig.ZOOM_NAME_COLOR);
        Page recipes = new Page("Recipes GUI")
            .toggle("Recipe Search Field", "A search field under JEI's recipe screen that filters the recipes shown. < ingredient, > result, ^ catalyst, | or, - not, @mod, #tooltip, $tag, &id. Extra (not in NEI).",
                AddonConfig.RECIPE_SEARCH)
            .toggle("Scroll To Pick Favorite Result", "Mouse wheel over a recipe's heart picks which of its results to favorite. Off: the heart uses the first result; Shift+F over a result slot favorites that one.",
                AddonConfig.HEART_SCROLL_RESULT)
            .toggle("Highlight Present Item", "Tint the ingredients of a recipe that are in your inventory.", AddonConfig.HIGHLIGHT_PRESENT);
        Page favorites = new Page("Favorites")
            .toggle("Show Recipe Tooltips in Right Panel", "Show an item's favorite recipe in a box beside its tooltip in JEI's item list, lookup history and the craftables panel (that one shows the recipe it crafts the item with).", AddonConfig.RECIPE_TIP_LIST)
            .toggle("Show Recipe Tooltips in Recipe Gui", "The same for items of JEI's recipe screen.", AddonConfig.RECIPE_TIP_GUI)
            .number("Depth", "How many levels a saved recipe tree goes down.", AddonConfig.FAVORITES_DEPTH, 0, 100)
            .toggle("Generate Single-Recipe Favorites", "An item with exactly one recipe uses it as its favorite when it has none of its own. Used by Shift+A, Shift+F and recipe trees.",
                AddonConfig.FAVORITES_AUTO);
        Page history = new Page("History Panel")
            .toggle("History As Wide As Item List", "Keeps JEI's lookup history (right side) as wide as the item list. Overwrites JEI's history column setting. Extra (not in NEI).",
                AddonConfig.HISTORY_MATCH_LIST);
        Page inventory = new Page("Inventory")
            .cycle("Recipe Mode: no utility buttons. Utility Mode: the buttons picked under Utilities. Cheat Mode: all of them. The buttons send commands, so the server must let you use them.",
                AddonConfig.CHEAT_MODE, "Recipe Mode", "Utility Mode", "Cheat Mode")
            .icons("Utilities", "Select features for utility mode",
                new Icon("Time", AddonConfig.UTIL_TIME, UtilityBar.Kind.NOON.u, UtilityBar.Kind.NOON.v),
                new Icon("Rain", AddonConfig.UTIL_RAIN, UtilityBar.Kind.RAIN.u, UtilityBar.Kind.RAIN.v),
                new Icon("Heal", AddonConfig.UTIL_HEAL, UtilityBar.Kind.HEAL.u, UtilityBar.Kind.HEAL.v),
                new Icon("Gamemode", AddonConfig.UTIL_GAMEMODE, UtilityBar.Kind.GAMEMODE.u, UtilityBar.Kind.GAMEMODE.v))
            .icons("Gamemodes", "Select the gamemodes to cycle through",
                new Icon("Creative", AddonConfig.GM_CREATIVE, UtilityBar.CREATIVE_ICON[0], UtilityBar.CREATIVE_ICON[1]),
                new Icon("Adventure", AddonConfig.GM_ADVENTURE, UtilityBar.ADVENTURE_ICON[0], UtilityBar.ADVENTURE_ICON[1]))
            .sub(bookmarks)
            .sub(recipes)
            .sub(history)
            .sub(craftables)
            .sub(zoom)
            .sub(favorites)
            .toggle("Show Hotkeys Help Text", "Show \"Hold ALT for hotkeys\" in item tooltips. Alt still shows the keys.", AddonConfig.HOTKEYS_HELP)
            .toggle("Show Hotkeys", "List the keys that work on an item in its tooltip while Alt is held.", AddonConfig.SHOW_HOTKEYS)
            .toggle("Show Item Quantity Widget", "The quantity field above the JEI item list.", AddonConfig.QUANTITY_WIDGET)
            .enabled("Autocrafting", "Allow autocrafting of bookmarked recipes and craftables.", AddonConfig.AUTOCRAFTING)
            .enabled("Item Untranslator", "Also find items by their English name when the game is not in English. Applies after the next JEI reload.", AddonConfig.UNTRANSLATE)
            .number("Item Quantity", "Amount for \"with count\" actions and the Ctrl+Alt+scroll step. 0 = one stack.", AddonConfig.ITEM_QUANTITY, 0,
                Integer.MAX_VALUE);
        Page world = new Page("World").sub(highlight);
        Page dumps = new Page("Data Dumps")
            .action("Dump Items", "", DataDumps::items)
            .action("Dump Blocks", "", DataDumps::blocks)
            .action("Dump Potion Effects", "", DataDumps::effects)
            .action("Dump Potions", "", DataDumps::potions)
            .action("Dump Entities", "", DataDumps::entities)
            .action("Dump Enchantments (in a world)", "", DataDumps::enchantments)
            .action("Dump Biomes (in a world)", "", DataDumps::biomes)
            .action("Dump JEI Item List (in a world)", "", DataDumps::jeiItems);
        Page tools = new Page("Tools").sub(dumps);
        return new Page("NEI Options").sub(inventory)
            .action("KeyBindings", "Configure NEI key bindings in the Minecraft controls screen", () -> {
                Minecraft mc = Minecraft.getInstance();
                McCompat.setScreen(new KeyBindsScreen(holder[0], mc.options));
            })
            .sub(world)
            .sub(tools)
            .action("JEI Options", "JEI's own settings (extra, not in NEI).", () -> {
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
    private Entry focus;
    private String text = "";
    private int caret;
    private boolean dragging;
    private int dragGrab;
    private Boolean tileThere;

    private NeiSettings(Screen parent, Page page) {
        super(Component.literal(page.title));
        this.parent = parent;
        this.page = page;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Esc: back to the screen the settings were opened from, whatever page this is. */
    @Override
    public void onClose() {
        Screen p = parent;
        while (p instanceof NeiSettings s) p = s.parent;
        McCompat.setScreen(p);
    }

    private static String shown(Entry e) {
        return switch (e.kind) {
            case TOGGLE -> e.names[(Boolean) e.value.get() ? 1 : 0];
            case CHOICE -> e.names[Math.max(0, Math.min((Integer) e.value.get(), e.names.length - 1))];
            default -> e.label;
        };
    }

    private static String display(Entry e) {
        return e.kind == Kind.COLOR ? String.format("#%06X", (Integer) e.value.get()) : String.valueOf(e.value.get());
    }

    @SuppressWarnings("unchecked")
    private static void setInt(Entry e, int v, boolean save) {
        ((ConfigSpec.ConfigValue<Integer>) e.value).set(Math.max(e.min, Math.min(e.max, v)));
        if (save) e.value.save();
    }

    private static void click() {
        Minecraft.getInstance()
            .getSoundManager()
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // ---------------------------------------------------------------- geometry

    private int slotW() {
        return Math.min(width - 80, MAX_SLOT_W);
    }

    private int slotX() {
        return (width - slotW()) / 2;
    }

    private int listH() {
        return height - 50;
    }

    private int listBottom() {
        return LIST_TOP + listH();
    }

    private int contentX() {
        return slotX() + LEFT_PAD;
    }

    private int contentW() {
        return slotW() - LEFT_PAD - RIGHT_PAD;
    }

    private int barX() {
        return slotX() + slotW() - RIGHT_PAD + (RIGHT_PAD - BAR_W) / 2;
    }

    private int maxScroll() {
        return Math.max(0, page.entries.size() * ROW_H - (listH() - 8));
    }

    private int rowY(int i) {
        return LIST_TOP + 4 + i * ROW_H - scroll + 2;
    }

    private int thumbH() {
        int total = page.entries.size() * ROW_H + 8;
        return Math.max(32, Math.min(listH(), (int) ((long) listH() * listH() / Math.max(1, total))));
    }

    private int thumbY() {
        int ms = maxScroll();
        return ms == 0 ? LIST_TOP : LIST_TOP + (int) ((long) (listH() - thumbH()) * scroll / ms);
    }

    private int backW() {
        return Math.min(200, width - 40);
    }

    private boolean overBack(double mx, double my) {
        int x = (width - backW()) / 2;
        return mx >= x && mx < x + backW() && my >= height - 25 && my < height - 25 + BTN_H;
    }

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

    private void tiles(GuiGraphics g, int x, int y, int w, int h) {
        if (h <= 0) return;
        if (tileThere()) g.blit(TILE, x, y, 0f, 0f, w, h, 32, 32);
        else g.fill(x, y, x + w, y + h, 0xFF2A2A2A);
    }

    /** NEI's button, cut from the game's widgets.png like LayoutManager.drawButtonBackground: 0 disabled, 1 normal, 2 hovered. */
    private static void button(GuiGraphics g, Font font, int x, int y, int w, int h, String label, int type) {
        int tiles = 0;
        int ew = w;
        if (w / 2 > 100) {
            tiles = (w - 200) / 50 + 1;
            ew = 200;
        }
        int w1 = ew / 2;
        int h1 = h / 2;
        int w2 = (ew + 1) / 2;
        int h2 = (h + 1) / 2;
        int x2 = x + w - w2;
        int y2 = y + h - h2;
        int ty = 46 + type * 20;
        int ty2 = ty + 20 - h2;
        int tx2 = 200 - w2;
        g.blit(WIDGETS, x, y, 0, ty, w1, h1);
        g.blit(WIDGETS, x, y2, 0, ty2, w1, h2);
        for (int tile = 0; tile < tiles; tile++) {
            int tx = x + w1 + 50 * tile;
            g.blit(WIDGETS, tx, y, 75, ty, 50, h1);
            g.blit(WIDGETS, tx, y2, 75, ty2, 50, h2);
        }
        g.blit(WIDGETS, x2, y, tx2, ty, w2, h1);
        g.blit(WIDGETS, x2, y2, tx2, ty2, w2, h2);
        if (label == null || label.isEmpty()) return;
        String t = font.width(label) > w - 4 ? font.plainSubstrByWidth(label, w - 4) : label;
        g.drawString(font, t, x + (w - font.width(t)) / 2, y + (h - 8) / 2, type == 0 ? 0xFFA0A0A0 : type == 2 ? 0xFFFFFFA0 : 0xFFE0E0E0, true);
    }

    private static void icon(GuiGraphics g, int u, int v, int x, int y) {
        Pose pose = Pose.of(g);
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(0.5f, 0.5f);
        g.blit(SPRITES, 0, 0, (float) u, (float) v, ICON, ICON, SHEET, SHEET);
        pose.popMatrix();
    }

    private void field(GuiGraphics g, Font font, int x, int y, int w, Entry e) {
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
        g.drawString(font, visible, x + pad, y + 6, 0xFFE0E0E0, true);
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cx = x + pad + (cut ? font.width(visible) : font.width(t.substring(0, Math.min(caret, t.length()))));
            g.fill(cx, y + 5, cx + 1, y + 15, 0xFFD0D0D0);
        }
    }

    private void fade(GuiGraphics g, int y, boolean down) {
        for (int k = 0; k < 4; k++) {
            int alpha = 255 - (int) (255 * (k + 0.5) / 4);
            int row = down ? y + k : y - 1 - k;
            g.fill(0, row, width, row + 1, alpha << 24);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        Font font = Minecraft.getInstance().font;
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
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
                    g.drawString(font, font.plainSubstrByWidth(e.label, avail), cx + PREFIX_X, y + 6, 0xFFFFFFFF, true);
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
        g.drawString(font, title, (width - font.width(title)) / 2, 6, 0xFFFFFFFF, true);
        button(g, font, (width - backW()) / 2, height - 25, backW(), BTN_H, "Back", overBack(mouseX, mouseY) ? 2 : 1);

        if (!tip.isEmpty()) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (String s : tip) lines.addAll(font.split(Component.literal(s), 260));
            g.renderTooltip(font, lines, mouseX, mouseY);
        }
    }

    // ---------------------------------------------------------------- editing a text field

    private void focus(Entry e) {
        if (focus == e) return;
        blur();
        focus = e;
        text = display(e);
        caret = text.length();
    }

    private void blur() {
        Entry e = focus;
        focus = null;
        if (e != null) e.value.save();
    }

    private static Long parse(Entry e, String s) {
        try {
            if (e.kind == Kind.COLOR) {
                String h = s.startsWith("#") ? s.substring(1) : s.startsWith("0x") || s.startsWith("0X") ? s.substring(2) : s;
                return h.isEmpty() || h.length() > 6 ? null : Long.valueOf(Long.parseLong(h, 16));
            }
            return s.isEmpty() ? null : Long.valueOf(Long.parseLong(s));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** NEI's number fields take the value as soon as the text is a valid one; what is not valid is dropped on leaving. */
    private void apply() {
        Entry e = focus;
        if (e == null) return;
        Long v = parse(e, text);
        if (v != null && v >= e.min && v <= e.max) setInt(e, (int) (long) v, false);
    }

    // ---------------------------------------------------------------- mouse

    private void mouse(double mx, double my, int button) {
        Font font = Minecraft.getInstance().font;
        if (overBack(mx, my)) {
            blur();
            click();
            McCompat.setScreen(parent);
            return;
        }
        int lb = listBottom();
        if (maxScroll() > 0 && mx >= barX() && mx < barX() + BAR_W && my >= LIST_TOP && my < lb) {
            blur();
            int ty = thumbY();
            dragGrab = my >= ty && my < ty + thumbH() ? (int) my - ty : thumbH() / 2;
            dragging = true;
            dragTo(my);
            return;
        }
        int hit = -1;
        if (my >= LIST_TOP && my < lb && mx >= contentX() && mx < contentX() + contentW()) {
            for (int i = 0; i < page.entries.size(); i++) {
                int y = rowY(i);
                if (my >= y && my < y + BTN_H) hit = i;
            }
        }
        if (hit < 0) {
            blur();
            return;
        }
        Entry e = page.entries.get(hit);
        Box b = box(e, font);
        boolean onBox = mx >= b.x() && mx < b.x() + b.w();
        if (focus != null && !(focus == e && onBox)) blur();
        if (!onBox) return;
        switch (e.kind) {
            case PAGE -> {
                click();
                McCompat.setScreen(new NeiSettings(this, e.page));
            }
            case ACTION -> {
                click();
                e.action.run();
            }
            case TOGGLE -> {
                click();
                @SuppressWarnings("unchecked")
                ConfigSpec.ConfigValue<Boolean> v = (ConfigSpec.ConfigValue<Boolean>) e.value;
                v.set(!v.get());
                v.save();
            }
            case CHOICE -> {
                click();
                int n = e.names.length;
                setInt(e, ((Integer) e.value.get() + (button == 1 ? -1 : 1) + n) % n, true);
            }
            case NUMBER -> clickField(e, b, font, mx);
            case COLOR -> {
                if (mx < b.x() + 18) {
                    click();
                    ConfigSpec.IntValue v = (ConfigSpec.IntValue) e.value;
                    McCompat.setScreen(new ColorPickerScreen(this, Component.literal(e.label), v::get, c -> {
                        v.set(c);
                        v.save();
                    }));
                } else {
                    clickField(e, b, font, mx);
                }
            }
            case ICONS -> {
                int k = (int) (mx - b.x()) / 24;
                if (k < 0 || k >= e.icons.size() || mx - b.x() - 24 * k >= BTN_H) return;
                ConfigSpec.BooleanValue v = e.icons.get(k)
                    .value();
                // NEI turns an icon on with the left button and off with the right one; here the left button switches it
                // both ways (an icon that is already on would otherwise look dead), the right button still turns it off
                if (button == 0 || button == 1 && v.get()) {
                    click();
                    v.set(button == 0 && !v.get());
                    v.save();
                }
            }
        }
    }

    private void clickField(Entry e, Box b, Font font, double mx) {
        focus(e);
        int pad = e.kind == Kind.COLOR ? 20 : 4;
        int inner = b.w() - pad - 4;
        if (font.width(text) > inner) {
            caret = text.length();
            return;
        }
        int rel = (int) mx - (b.x() + pad);
        int c = 0;
        while (c < text.length() && font.width(text.substring(0, c + 1)) - font.width(text.substring(c, c + 1)) / 2 <= rel) c++;
        caret = c;
    }

    private void dragTo(double my) {
        int span = listH() - thumbH();
        if (span <= 0) return;
        scroll = (int) ((my - dragGrab - LIST_TOP) * maxScroll() / span);
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    // ---------------------------------------------------------------- events

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s)) return;
        event.setCanceled(true);
        s.mouse(event.getMouseX(), event.getMouseY(), event.getButton());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s)) return;
        s.dragging = false;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s) || !s.dragging) return;
        event.setCanceled(true);
        s.dragTo(event.getMouseY());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s)) return;
        s.scroll -= (int) Math.signum(event.getScrollDelta()) * ROW_H;
        s.scroll = Math.max(0, Math.min(s.scroll, s.maxScroll()));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onKey(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s) || s.focus == null) return;
        event.setCanceled(true);
        int code = event.getKeyCode();
        if (code == GLFW.GLFW_KEY_ENTER || code == GLFW.GLFW_KEY_KP_ENTER || code == GLFW.GLFW_KEY_ESCAPE) {
            s.blur();
        } else if (code == GLFW.GLFW_KEY_BACKSPACE && s.caret > 0) {
            s.text = s.text.substring(0, s.caret - 1) + s.text.substring(s.caret);
            s.caret--;
            s.apply();
        } else if (code == GLFW.GLFW_KEY_DELETE && s.caret < s.text.length()) {
            s.text = s.text.substring(0, s.caret) + s.text.substring(s.caret + 1);
            s.apply();
        } else if (code == GLFW.GLFW_KEY_LEFT) {
            s.caret = Math.max(0, s.caret - 1);
        } else if (code == GLFW.GLFW_KEY_RIGHT) {
            s.caret = Math.min(s.text.length(), s.caret + 1);
        } else if (code == GLFW.GLFW_KEY_HOME) {
            s.caret = 0;
        } else if (code == GLFW.GLFW_KEY_END) {
            s.caret = s.text.length();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void onChar(ScreenEvent.CharacterTyped.Pre event) {
        if (!(event.getScreen() instanceof NeiSettings s) || s.focus == null) return;
        event.setCanceled(true);
        int c = event.getCodePoint();
        boolean color = s.focus.kind == Kind.COLOR;
        boolean ok = color ? c < 128 && (Character.digit(c, 16) >= 0 || c == '#' || c == 'x' || c == 'X') : c >= '0' && c <= '9';
        if (!ok || s.text.length() >= (color ? 8 : 10)) return;
        s.text = s.text.substring(0, s.caret) + (char) c + s.text.substring(s.caret);
        s.caret++;
        s.apply();
    }
}
