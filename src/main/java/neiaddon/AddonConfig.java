package neiaddon;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AddonConfig {

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    // NEI: world.highlight_tips (default off, toggled with Numpad 0), .x = 5000, .y = 100 (of 10000)
    public static final ModConfigSpec.BooleanValue HUD_ENABLED = B
        .comment("Show the name of the block under the crosshair (NEI 'highlight tips'). Toggle in game with Numpad 0.")
        .define("hudEnabled", false);

    public static final ModConfigSpec.IntValue HUD_X = B
        .comment("Horizontal position of the highlight tip, 0 (left) to 10000 (right). NEI default 5000.")
        .defineInRange("hudX", 5000, 0, 10000);

    public static final ModConfigSpec.IntValue HUD_Y = B
        .comment("Vertical position of the highlight tip, 0 (top) to 10000 (bottom). NEI default 100.")
        .defineInRange("hudY", 100, 0, 10000);

    public static final ModConfigSpec.BooleanValue HUD_SHOW_MOD = B
        .comment("Extra (not in NEI): show the mod name in the highlight tip.")
        .define("hudShowMod", false);

    public static final ModConfigSpec.BooleanValue HUD_SHOW_ID = B
        .comment("Extra (not in NEI): show the registry id (namespace:path) in the highlight tip.")
        .define("hudShowId", false);

    // NEI: the item quantity field. 0 = one stack of the item.
    public static final ModConfigSpec.IntValue ITEM_QUANTITY = B
        .comment("Quantity of the item quantity field (items 'with count' and the Ctrl+Alt+scroll step). 0 = one stack.")
        .defineInRange("itemQuantity", 0, 0, Integer.MAX_VALUE);

    // NEI: craftingTreeZoomWithoutModifier
    public static final ModConfigSpec.BooleanValue TREE_ZOOM_WITHOUT_CTRL = B
        .comment("Zoom the crafting tree with the plain mouse wheel (otherwise Ctrl + wheel zooms and the wheel scrolls).")
        .define("treeZoomWithoutCtrl", true);

    static final ModConfigSpec SPEC = B.build();

    private AddonConfig() {}
}
