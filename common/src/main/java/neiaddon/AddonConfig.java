package neiaddon;

import neiaddon.config.ConfigSpec;

public final class AddonConfig {

    private static final ConfigSpec.Builder B = new ConfigSpec.Builder();

    // NEI: world.highlight_tips (default off, toggled with Numpad 0), .x = 5000, .y = 100 (of 10000)
    public static final ConfigSpec.BooleanValue HUD_ENABLED = B
        .comment("Show the name of the block under the crosshair (NEI 'highlight tips'). Toggle in game with Numpad 0.")
        .define("hudEnabled", false);

    public static final ConfigSpec.IntValue HUD_X = B
        .comment("Horizontal position of the highlight tip, 0 (left) to 10000 (right). NEI default 5000.")
        .defineInRange("hudX", 5000, 0, 10000);

    public static final ConfigSpec.IntValue HUD_Y = B
        .comment("Vertical position of the highlight tip, 0 (top) to 10000 (bottom). NEI default 100.")
        .defineInRange("hudY", 100, 0, 10000);

    public static final ConfigSpec.BooleanValue HUD_SHOW_MOD = B
        .comment("Extra (not in NEI): show the mod name in the highlight tip.")
        .define("hudShowMod", false);

    public static final ConfigSpec.BooleanValue HUD_SHOW_ID = B
        .comment("Extra (not in NEI): show the registry id (namespace:path) in the highlight tip.")
        .define("hudShowId", false);

    // NEI: the item quantity field. 0 = one stack of the item.
    public static final ConfigSpec.IntValue ITEM_QUANTITY = B
        .comment("Quantity of the item quantity field (items 'with count' and the Ctrl+Alt+scroll step). 0 = one stack.")
        .defineInRange("itemQuantity", 0, 0, Integer.MAX_VALUE);

    // NEI: craftingTreeZoomWithoutModifier
    public static final ConfigSpec.BooleanValue TREE_ZOOM_WITHOUT_CTRL = B
        .comment("Zoom the crafting tree with the plain mouse wheel (otherwise Ctrl + wheel zooms and the wheel scrolls).")
        .define("treeZoomWithoutCtrl", true);

    // NEI: inventory.craftables.*
    public static final ConfigSpec.BooleanValue CRAFTABLES_ENABLED = B
        .comment("Show the craftables panel: everything the open container can craft right now, above the quantity field.")
        .define("craftablesEnabled", false);

    public static final ConfigSpec.IntValue CRAFTABLES_ROWS = B
        .comment("Rows of the craftables panel (NEI useRows).")
        .defineInRange("craftablesRows", 2, 1, 5);

    public static final ConfigSpec.BooleanValue CRAFTABLES_FAVORITES_ONLY = B
        .comment("Only list items whose favorite recipe can be crafted (NEI favoritesOnly).")
        .define("craftablesFavoritesOnly", false);

    public static final ConfigSpec.BooleanValue HISTORY_MATCH_LIST = B
        .comment("Keep JEI's lookup history list as wide as the item list (only acts when the history is shown on the right). Overwrites JEI's own history column setting while on.")
        .define("historyMatchesListWidth", true);

    // NEI: inventory.showItemQuantityWidget / hotkeysHelpText / autocrafting
    public static final ConfigSpec.BooleanValue QUANTITY_WIDGET = B
        .comment("Show the item quantity field above the JEI item list (NEI showItemQuantityWidget).")
        .define("showItemQuantityWidget", true);

    public static final ConfigSpec.BooleanValue HOTKEYS_HELP = B
        .comment("Show 'Hold ALT for hotkeys' in item tooltips (NEI hotkeysHelpText). Alt still shows the keys.")
        .define("hotkeysHelpText", true);

    public static final ConfigSpec.BooleanValue AUTOCRAFTING = B
        .comment("Allow autocrafting of bookmarked recipes and craftables (NEI autocrafting).")
        .define("autocrafting", true);

    public static final ConfigSpec.BooleanValue HEART_SCROLL_RESULT = B
        .comment("Mouse wheel over a recipe's heart picks which result to favorite (as NEI does). Off: the heart uses the first result, and Shift+F over a result slot favorites that result.")
        .define("heartScrollResult", false);

    // NEI: the search field of the recipe GUI
    public static final ConfigSpec.BooleanValue RECIPE_SEARCH = B
        .comment("Show a search field under JEI's recipe screen that filters the recipes shown (NEI recipe search syntax).")
        .define("recipeSearch", true);

    // NEI: inventory.itemzoom.*
    public static final ConfigSpec.BooleanValue ZOOM_ENABLED = B
        .comment("Draw the hovered item large beside the inventory (NEI itemzoom.enabled). Shift+Z toggles it in game.")
        .define("itemZoomEnabled", true);

    public static final ConfigSpec.BooleanValue ZOOM_OVERLAYS_ONLY = B
        .comment("Only zoom items of the JEI item list and the bookmark panel (NEI neiOnly).")
        .define("itemZoomOverlaysOnly", true);

    public static final ConfigSpec.BooleanValue ZOOM_ONLY_SOLID = B
        .comment("Only zoom blocks that are solid (NEI onlySolid).")
        .define("itemZoomOnlySolid", false);

    public static final ConfigSpec.IntValue ZOOM_SIDE = B
        .comment("Side of the zoom: 0 auto (the side the mouse is not on), 1 left, 2 right.")
        .defineInRange("itemZoomSide", 1, 0, 2);

    public static final ConfigSpec.BooleanValue ZOOM_HELP = B
        .comment("Show the name 'Item Zoom' and its toggle key below the zoomed item (NEI helpText).")
        .define("itemZoomHelpText", true);

    public static final ConfigSpec.IntValue ZOOM_AMOUNT = B
        .comment("How much of the free side space the zoomed item fills, 0 to 1000 (NEI zoom).")
        .defineInRange("itemZoomAmount", 500, 0, 1000);

    public static final ConfigSpec.BooleanValue ZOOM_NAME = B
        .comment("Show the item name below the zoomed item (NEI showName).")
        .define("itemZoomShowName", true);

    public static final ConfigSpec.IntValue ZOOM_NAME_COLOR = B
        .comment("Color of the zoomed item's name (RGB). Change it in the NEI Features options (Inventory, Item Zoom).")
        .defineInRange("itemZoomNameColor", 0xFFFFFF, 0, 0xFFFFFF);

    // NEI: inventory.bookmarks.*
    public static final ConfigSpec.IntValue BM_TOOLTIP_MODE = B
        .comment("Recipe picture in the bookmark tooltip (NEI recipeTooltipsMode): 0 never, 1 default view, 2 todo-list view, 3 always.")
        .defineInRange("bookmarkRecipeTooltips", 1, 0, 3);

    public static final ConfigSpec.IntValue BM_MARKER_MODE = B
        .comment("Recipe marker on bookmarked recipes (NEI showRecipeMarkerMode): 0 first result only, 1 every result with an R, 2 tinted background.")
        .defineInRange("bookmarkRecipeMarker", 0, 0, 2);

    public static final ConfigSpec.IntValue BM_MARKER_COLOR = B
        .comment("Color of the recipe markers (RGB). Change it in the NEI Features options (Inventory, Bookmarks Panel).")
        .defineInRange("bookmarkMarkerColor", 0xADADAD, 0, 0xFFFFFF);

    public static final ConfigSpec.BooleanValue BM_HANDLER_ICON = B
        .comment("Show the recipe type's icon instead of the R marker (NEI showRecipeHandlerIcon).")
        .define("bookmarkHandlerIcon", false);

    public static final ConfigSpec.BooleanValue BM_WORLD_SPECIFIC = B
        .comment("Keep a separate bookmark list for every world and server (NEI worldSpecific).")
        .define("bookmarksWorldSpecific", false);

    static final ConfigSpec SPEC = B.build();

    private AddonConfig() {}
}
