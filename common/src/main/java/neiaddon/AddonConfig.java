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

    public static final ConfigSpec.BooleanValue AUTOCRAFTING_FAST = B
        .comment("Faster Autocrafting: when a full crafting grid would make more than is wanted, click the result once per wanted craft instead of crafting one set at a time. Every click waits for the server and is checked; anything unexpected stops autocrafting and puts the cursor and grid back into the inventory. Extra (not in NEI).")
        .define("autocraftingFast", false);

    public static final ConfigSpec.BooleanValue AUTOCRAFTING_RESTORE = B
        .comment("After autocrafting, move the items that were in the inventory back to the slots they were in before (one checked click at a time). Crafted items stay in the slots they landed in.")
        .define("autocraftingRestoreLayout", false);

    public static final ConfigSpec.BooleanValue COLLAPSIBLE_ITEMS = B
        .comment("NEI's collapsible item groups: families of items (wool colours, spawn eggs, ...) show as one entry in JEI's list; Alt + click opens or closes a group. Groups are in config/neiaddon/collapsibleitems.cfg. Takes the members out of JEI's list while closed.")
        .define("collapsibleItems", false);

    public static final ConfigSpec.BooleanValue HEART_SCROLL_RESULT = B
        .comment("Mouse wheel over a recipe's heart picks which result to favorite (as NEI does). Off: the heart uses the first result, and Shift+F over a result slot favorites that result.")
        .define("heartScrollResult", true);

    public static final ConfigSpec.BooleanValue FLUID_COMPACT = B
        .comment("Short fluid amounts in slots: 500mB, then buckets from 1000 mB (2.5B). Off: millibuckets up to 9999 (2500mB), which can run into the next slot.")
        .define("compactFluidAmounts", true);

    // NEI: inventory.favorites.*
    public static final ConfigSpec.BooleanValue FAVORITES_AUTO = B
        .comment("Use the only recipe of an item as its favorite when the item has exactly one recipe and no favorite of its own (NEI generateSingleRecipeFavorites).")
        .define("generateSingleRecipeFavorites", false);

    public static final ConfigSpec.IntValue FAVORITES_DEPTH = B
        .comment("How many levels a recipe tree goes down when it is saved (NEI inventory.favorites.depth, default 9).")
        .defineInRange("favoritesDepth", 9, 0, 100);

    public static final ConfigSpec.BooleanValue RECIPE_TIP_LIST = B
        .comment("Show the favorite recipe of an item of JEI's item list, lookup history or craftables panel in a box beside its tooltip (NEI showRecipeTooltipInPanel). The craftables panel shows the recipe it crafts the item with.")
        .define("recipeTooltipInList", false);

    public static final ConfigSpec.BooleanValue RECIPE_TIP_GUI = B
        .comment("Show the favorite recipe of an item of JEI's recipe screen in a box beside its tooltip (NEI showRecipeTooltipInGui).")
        .define("recipeTooltipInRecipeScreen", false);

    public static final ConfigSpec.BooleanValue HIGHLIGHT_PRESENT = B
        .comment("Tint the ingredients of a recipe in JEI's recipe screen that are in your inventory (NEI slotHighlightPresent).")
        .define("highlightPresentItems", false);

    public static final ConfigSpec.IntValue BM_TIP_ROWS = B
        .comment("Most rows of icons in a bookmark group's crafting chain tooltip, 0 for no limit (NEI maxVerticalTooltipRows).")
        .defineInRange("bookmarkTooltipRows", 5, 0, 50);

    public static final ConfigSpec.IntValue KEY_DEFAULTS = B
        .comment("Internal: set once the addon has put its unbound keys back on their defaults (done once per version of that fix).")
        .defineInRange("keyDefaultsDone", 0, 0, 100);

    public static final ConfigSpec.BooleanValue RELEASE_NOTE = B
        .comment("On joining a world, a chat message asks to report issues with this release on GitHub.")
        .define("releaseNote", true);

    public static final ConfigSpec.BooleanValue WARN_JEI_PLUS_PLUS = B
        .comment("On joining a world, say in chat that JEI++ (when installed) clashes with this addon.")
        .define("warnJeiPlusPlus", true);

    public static final ConfigSpec.BooleanValue BM_JEI_BOOKMARKS = B
        .comment("What JEI's own bookmark button (on each recipe) and its bookmark key add goes into this addon's bookmark panel instead of JEI's own list.")
        .define("jeiBookmarksToPanel", true);

    public static final ConfigSpec.IntValue BM_MARGIN_TOP = B
        .comment("Extra space above the bookmark panel, in pixels, for a mod that draws on the screen without telling anyone (the panel already moves out of the way of buttons it can find).")
        .defineInRange("bookmarkMarginTop", 0, 0, 400);

    public static final ConfigSpec.IntValue BM_MARGIN_LEFT = B
        .comment("Extra space left of the bookmark panel, in pixels.")
        .defineInRange("bookmarkMarginLeft", 0, 0, 400);

    public static final ConfigSpec.BooleanValue BM_ANIMATION = B
        .comment("Slide bookmarks to their new place when the panel rearranges (NEI's REI style animation).")
        .define("bookmarkAnimation", false);

    public static final ConfigSpec.BooleanValue SHOW_HOTKEYS = B
        .comment("List the keys that work on an item in its tooltip while Alt is held (NEI showHotkeys).")
        .define("showHotkeys", true);

    // NEI: inventory.cheatmode (0 recipe mode, 1 utility mode, 2 cheat mode), inventory.utilities, inventory.gamemodes
    public static final ConfigSpec.IntValue CHEAT_MODE = B
        .comment("Utility buttons above the bookmark panel (NEI cheat mode): 0 recipe mode (none), 1 utility mode (the ones picked below), 2 cheat mode (all, and a click on an item gives it).")
        .defineInRange("cheatMode", 0, 0, 2);
    public static final ConfigSpec.BooleanValue UTIL_TIME = B
        .comment("Utility mode: the four time of day buttons.")
        .define("utilityTime", true);
    public static final ConfigSpec.BooleanValue UTIL_RAIN = B
        .comment("Utility mode: the rain button.")
        .define("utilityRain", true);
    public static final ConfigSpec.BooleanValue UTIL_HEAL = B
        .comment("Utility mode: the heal button.")
        .define("utilityHeal", true);
    public static final ConfigSpec.BooleanValue UTIL_GAMEMODE = B
        .comment("Utility mode: the game mode button.")
        .define("utilityGamemode", true);
    public static final ConfigSpec.BooleanValue GM_CREATIVE = B
        .comment("The game mode button cycles to creative.")
        .define("gamemodeCreative", true);
    public static final ConfigSpec.BooleanValue GM_ADVENTURE = B
        .comment("The game mode button cycles to adventure.")
        .define("gamemodeAdventure", true);

    public static final ConfigSpec.BooleanValue UNTRANSLATE = B
        .comment("Also find items by their English name when the game is not in English (NEI itemUntranslator). Applies after the next JEI reload.")
        .define("itemUntranslator", false);

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
        .define("itemZoomOverlaysOnly", false);

    public static final ConfigSpec.BooleanValue ZOOM_ONLY_SOLID = B
        .comment("Only zoom blocks that are solid (NEI onlySolid).")
        .define("itemZoomOnlySolid", false);

    public static final ConfigSpec.IntValue ZOOM_SIDE = B
        .comment("Side of the zoom (NEI preferredSide): 0 auto (the side whose panel holds fewer items, the other one when the mouse is over it), 1 left, 2 right.")
        .defineInRange("itemZoomSide", 0, 0, 2);

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

    public static final ConfigSpec.BooleanValue BM_VISIBLE = B
        .comment("Show the bookmark panel (NEI bookmarks.enabled). The bookmarks key and the bookmarks button in JEI's button row switch it too.")
        .define("bookmarksVisible", true);

    public static final ConfigSpec.BooleanValue BM_ITEMS_WITH_RECIPE = B
        .comment("A bookmarks key press (A) over an item inside a recipe of JEI's recipe screen saves the recipe with the item, and its tooltip then shows that recipe (NEI bookmarkItemsWithRecipe).")
        .define("bookmarkItemsWithRecipe", true);

    public static final ConfigSpec.BooleanValue BM_RECIPE_WITH_COUNT = B
        .comment("A recipe saved with Shift+A starts with a count of one run (NEI bookmarkRecipeWithCount). Off: it starts without a count and Ctrl+Shift+A saves it with one.")
        .define("bookmarkRecipeWithCount", true);

    static final ConfigSpec SPEC = B.build();

    private AddonConfig() {}
}
