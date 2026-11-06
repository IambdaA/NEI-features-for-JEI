# NEI Features for JEI (26.1.2, NeoForge and Fabric) - build notes

Status: builds and runs in game on NeoForge and Fabric. Not every feature has been tried on every loader; bug reports are welcome.

## Build
1. JDK 25 (Gradle itself runs on it; `gradle/gradle-daemon-jvm.properties` makes Gradle find or download one, otherwise point `JAVA_HOME` at a JDK 25). 2. `./gradlew clean build` (Windows: `gradlew.bat clean build`), or `./gradlew :neoforge:build` / `./gradlew :fabric:build` for one loader. 3. `./gradlew :neoforge:runClient` or `./gradlew :fabric:runClient` starts a dev client with JEI included.
Jars: `neoforge/build/libs/neiaddon-neoforge-26.1.2-<version>.jar`, `fabric/build/libs/neiaddon-fabric-26.1.2-<version>.jar`.
Versions are in gradle.properties: `neo_version`, `jei_version`, and for Fabric `loom_version` (1.18-SNAPSHOT), `fabric_loader_version`, `fabric_api_version`, `mezz_config_version` (JEI's config library, needed in the Fabric dev client). Change any of them if the build can't resolve it.
If Loom says it needs another Gradle, run `./gradlew wrapper --gradle-version <that version>` (the wrapper is 9.7.0).

## Layout (multiloader)
- `common/` - the shared code and assets, plain folders: both loader projects compile `common/src/main/java` and `common/src/mc26_1/java` (the few classes that differ between Minecraft versions, `McCompat`) into their own jar, so each jar is complete. It uses only vanilla and JEI API classes (no `net.neoforged`, no `net.fabricmc`).
- `neoforge/` - `NeiAddonNeoForge` / `NeiAddonNeoForgeClient` (the `@Mod` entry points), `NeoEvents` (hooks NeoForge's screen, tick and tooltip events to the addon's bus, one listener per priority), `NeoPlatform`, `NeoClientPlatform`, `neoforge.mods.toml`.
- `fabric/` - `NeiAddonFabric` / `NeiAddonFabricClient` (entry points), `FabricEvents` (the same hooks on Fabric API's per-screen events, in phases ordered like NeoForge's priorities), four small mixins (typed characters, screen drawing, container screen drawing, end of frame), an access widener (container screen size and hovered slot), `fabric.mod.json` (JEI plugin entry point `jei_mod_plugin`).
- What the shared code needs from a loader is in `neiaddon.platform` (config folder, mod names, the optional chat link channel, a container screen's bounds and hovered slot). The handler classes use the addon's own `neiaddon.event` (`@SubscribeEvent`, `EventPriority`, `ScreenEvent`..., same names and getters as NeoForge's, run by `Bus`), and the settings are `neiaddon.config.ConfigSpec`, which reads and writes `config/neiaddon-client.toml`.
<<<<<<< Updated upstream
- Fabric differences: the settings screen opens from the gear button on container screens (no Mod Menu entry), and the highlight tips HUD is not hidden by F1.
=======
- Fabric differences: the settings screen opens from the N button in JEI's bottom-left button row (no Mod Menu entry), and the highlight tips HUD is not hidden by F1.
>>>>>>> Stashed changes

## Features
- **Highlight tips** (as NEI's HUDRenderer): a tooltip-style box with the item and name of the BLOCK under the crosshair (not entities). Off by default; Numpad 0 toggles it, the player-list key (Tab) hides it. Position `hudX`/`hudY` (0..10000, NEI defaults 5000/100) in `neiaddon-client.toml`; bind "Move Highlight Tooltips" in Controls to open NEI's drag-to-position screen. Extras not in NEI (off by default): `hudShowMod`, `hudShowId`.
- **Item info pages**: `config/neiaddon/informationpages.cfg` (created on first start), NEI's `filter = text` format. Filters: `namespace:path` (with `*`), `$namespace:tag` (NEI's `$oredict`; `$name` means `c:name`), `@modid`. Lines starting with `#` are comments, as in NEI. Shown in JEI on the item's recipe page.
- **Bookmarks (modelled on GTNH NotEnoughItems' BookmarkPanel)**: a grid of item slots on the left of container screens and JEI's recipe screen. Drag items in from JEI's list.
  - Group strip (7px, left of the slots): drag with the left button to create a group or extend the one you start in, right button to exclude rows. A plain click on a group: LMB = toggle group mode (todo list: one item per row), RMB = toggle crafting chain (green bracket), Alt+LMB = collapse. Shift+LMB drag = move the group. Ctrl+scroll = change the quantity of the whole group (Ctrl+Alt = multiplier 64).
  - Slot: LMB = recipes, RMB = uses, Shift+LMB drag = move (a recipe item moves its whole recipe), Ctrl+scroll = amount (Ctrl+Alt = stack size; on a recipe item it changes how often the whole recipe runs), Alt+LMB re-expands a collapsed group; in a crafting chain it collapses/expands the slot's recipe into one merged block (the recipe's results, then what it and the recipes it feeds on need; boxed in purple). Shift+scroll on an ingredient that accepts several items (tag) switches the item. Alt+LMB on the page label collapses the ungrouped items. Amounts are drawn at half size bottom right (only when above 0).
  - Top `< 1/2 >`: grid pages (scroll too). The label is the handle of the ungrouped items: LMB = todo mode, RMB = crafting chain, Ctrl+scroll = quantity. The brackets turn green when the chain is on.
  - Bottom `< 1/3 >`: bookmark pages ("namespaces"). Like NEI there is no add button: an empty page always follows the last used one, and empty pages vanish when you leave them. Scroll over the buttons to switch. Saved in `config/neiaddon/bookmarks.txt`.
- **Recipe tree (favorites + recipe bookmarks, as NEI)**: every recipe in JEI's recipe screen has a heart button next to JEI's own buttons (added through JEI's `addRecipeButtonFactory`, so no mixin).
  - Click the heart: this recipe becomes the favorite recipe of its result item (red heart = favorite). The heart uses the first result of the recipe; `Shift+F` over a result slot favorites that result instead, and with `heartScrollResult` on in the config (off by default) the wheel over the heart picks the result as in NEI (one favorite per item). Saved in `config/neiaddon/favorites.txt` (`itemId <tab> recipeId`).
  - `Shift+A` while the mouse is over a heart: saves the recipe tree (the recipe, the favorite recipe of each ingredient, and so on, depth 9, nothing is searched automatically) as ONE new bookmark group in todo-list view with the crafting chain on. `Shift+T` over a heart saves it and opens the calculator for that group. `Shift+A` over a recipe: bookmarks / un-bookmarks just that recipe (only the hovered result) in the ungrouped items.
  - Todo-list view: one recipe per row (result first, then its ingredients; ingredients that do not fit continue from the second column of the next row). In a crafting-chain group the amounts are calculated by the ported NEI chain maths (how often every sub-recipe has to run so the top recipe gets everything it needs; your own plain items are spent first); the first result of an output recipe has a small `xN` marker (N = its multiplier). Normal view of a chain group shows only results and plain items. `Ctrl+wheel` on a recipe item changes how often the whole recipe runs (`Ctrl+Alt` = stack size steps), `Ctrl+wheel` on the group strip scales the whole group, `A` over a recipe item removes it (a recipe's only result removes the whole recipe), `Shift+A` removes the whole recipe, RMB on the strip toggles the crafting chain. Hovering the strip of a chain group lists the raw materials it still needs.
  - Recipe ids: JEI's recipe identifier (`recipeTypeUid|registry id`), or for recipes without one a hash of the category and slots (`recipeTypeUid#hash`). Bookmark file lines: `item <id> <amount> <group> <type 0/1/2> <recipeId|-> <factor> <option ids>`, group flags in `meta` lines. Lines of uninstalled items are kept untouched and shown as nothing.
  - The chain maths is plain Java (`neiaddon.chain`, no Minecraft classes) and is checked by `tools/ChainMathCheck.java`; the model and the layout by `tools/ModelCheck.java` / `tools/LayoutCheck.java` against stand-in classes (`tools/make_stubs.py`, see the headers of those files). These checks never touch the real game: they only catch mistakes in this addon's own code.
- **Crafting calculator** (per group): steps and raw materials; click a step = other recipe, right click = treat as raw, +/- adjust amounts. Works with any recipe type JEI knows.
- **Keys (Controls, NEI defaults)**: `A` toggles a bookmark for the hovered item (JEI list, recipe screen or inventory slot; over a bookmark it removes it; Ctrl+A keeps the stack size), `Shift+A` over a group strip or the page label removes the group, `T` over a group strip or the label opens the crafting tree window when that group is in crafting-chain mode, `B` hides/shows the panel. `T` (own key, "Search Name in AE2 Terminal") over an item or fluid in an Applied Energistics 2 screen types its name into the terminal's search box. Fluid amounts are shown in mB (buckets once large). `R`/`U` are JEI's own keys and already match NEI. Ignored while the JEI search box has focus.
<<<<<<< Updated upstream
=======
- **More NEI features (settings under Inventory and Tools, keys in Controls):**
  - The settings screen (mod list button, or the N button in JEI's bottom-left button row) is laid out like NEI's options screen: a column of at most 320 px with a thin scroll bar, the tiled header and footer, the page's name at the top and a Back button. Inventory holds Cheat Mode, the Utilities and Gamemodes icon rows (a lit icon is on: left click turns one on, right click off), the sub pages Bookmarks Panel, Recipes GUI, History Panel, Craftables Panel, Item Zoom Panel and Favorites, and the yes/no options with NEI's names and value texts (Yes / No, Enabled / Disabled). A number or a color is a text field (a valid value applies as you type; a color also has a swatch that opens the color picker). The root also has KeyBindings (the controls screen), World (Highlight Tooltips), Tools > Data Dumps and JEI Options. It does not pause the game and Esc goes back to where you came from. NEI's Item Panel Sorting, Search Widget, Subsets, Collapsible Items, Item IDs, item panel scale, mouse wheel transfer and per-world (W) overrides are not here: this addon has nothing for them.
  - `generateSingleRecipeFavorites` (off, as NEI): an item with exactly one recipe in JEI uses it as its favorite when it has no own favorite (used by Shift+A, Shift+F, the recipe tree and the crafting key). `favoritesDepth` (default 9, 0 to 100): how many levels a saved recipe tree goes down.
  - `recipeTooltipInList` / `recipeTooltipInRecipeScreen` (off): the favorite recipe of the hovered item as a picture in a small box beside its tooltip, in JEI's item list, lookup history and craftables panel (the craftables panel shows the recipe it crafts the item with) / in JEI's recipe screen (JEI draws those tooltips itself, so the picture cannot be a part of them as in NEI).
  - `bookmarkItemsWithRecipe` (on) and `bookmarkRecipeWithCount` (on here, off in NEI): the bookmarks key (A) over an item inside a recipe of JEI's recipe screen saves the recipe with the item, and the item's bookmark tooltip then shows that recipe's picture (same rules as for recipe bookmarks). A recipe saved with Shift+A over a recipe, a favorite item or a heart starts with a count of one run, or without a count when the option is off (Ctrl+Shift+A then saves it with one).
  - `highlightPresentItems` (off): ingredients of the recipes on JEI's recipe screen that the open container's inventory holds (in the amount the recipe needs) get a green tint.
  - Fill key (`S`, "Fill Crafting Grid with Recipe"): over a recipe of JEI's recipe screen it moves that recipe's ingredients into the open crafting grid, like JEI's "+" button; Shift moves as many crafts as fit.
  - `bookmarkTooltipRows` (5, 0 = no limit): most rows of icons in a group's chain tooltip. `bookmarkAnimation` (off): bookmarks slide to their new place when the panel rearranges.
  - `showHotkeys` (on): off removes the hotkey list and the "Hold ALT for hotkeys" line from tooltips.
  - Utility keys, unbound by default, each sends the command that does it (so the server's permissions apply): time to dawn / noon / dusk / midnight (`time set 0/6000/12000/18000`), toggle rain (`weather`), heal and feed (`effect give`), toggle creative (`gamemode`).
  - The options button works like NEI's: the **N** button in JEI's bottom-left row opens the settings, and **Ctrl + click** on it cycles Recipe Mode, Utility Mode and Cheat Mode (its tooltip shows the mode: gold for Utility, red for Cheat). Recipe Mode shows no utility buttons, Utility Mode only the ones ticked in Inventory > Utilities (the Gamemodes row picks the modes the C button goes through, in Utility and Cheat Mode), Cheat Mode all of them. Bookmarks Panel Visibility is a saved setting (the bookmarks key and JEI's bookmarks button switch it).
  - Defaults that differ from NEI's: Cheat Mode (NEI: Cheat Mode, here Recipe Mode), REI Style Animation in Bookmarks, Show Recipe Tooltips in Right Panel, Highlight Present Item and Item Untranslator (NEI: on, here off), NEI Overlays Only (NEI: off, here on), Item Zoom Preferred Side (NEI: Auto, here Left) and Tree Zoom without Ctrl (NEI: off, here on) and Bookmark Recipe With Count (NEI: off, here on).
  - Utility buttons (`cheatMode`, Settings > Inventory > Cheat Mode, off = Recipe Mode): a row of NEI's icon buttons above the bookmark panel for the same commands: rain, game mode (cycles survival, creative, adventure; the modes are picked in the Gamemodes row), dawn / noon / dusk / midnight, heal. Utility Mode shows the ones picked in Settings > Inventory > Utilities, Cheat Mode shows all. NEI's delete, magnet, enchant, potion and item buttons are not here (they need NEI's own server side). The icons are NEI's `nei_sprites.png`.
  - Other mods' buttons: the bookmark panel keeps clear of what other mods put on the inventory screen (FTB Quests, Teams and Utilities, Curios, ...). It reads the areas those mods register with JEI and, independently, the visible buttons on the screen itself (`ScreenAreas`), joins ones that touch into one box and moves around it (to the side where the panel keeps the most room, else below or above). The log line `Bookmark panel on <screen>: N area(s) from JEI, M widget(s) from the screen [x,y WxH ...]` says what it found. Something drawn without a button or a JEI area cannot be found; `bookmarkMarginTop` and `bookmarkMarginLeft` (Settings > Bookmarks Panel > Panel Margin Top / Left) push the panel away by hand for that.
  - JEI's own bookmark button (`jeiBookmarksToPanel`, Settings > Bookmarks Panel): JEI has no API for its bookmark list, so `JeiBookmarks` finds JEI's `BookmarkList` by reflection (by class name, below the runtime), looks at it a few times a second and moves what JEI added into the panel: a recipe bookmark (the button on each recipe, or JEI's key over a recipe) becomes what JEI's own list shows for it: the recipe's result as one item with the recipe type's icon in its corner, whose tooltip says `[Hold "SHIFT" for recipe details]` and shows the recipe picture while Shift is held (a plain item that remembers its recipe, like the bookmarks key over an item inside a recipe, which now also gets the icon); an item bookmark becomes a plain bookmark; JEI's list stays empty. Pressing JEI's button again for a recipe the panel has takes it out again (JEI's button itself never shows as lit). The panel always leaves JEI's bottom button row free (`JeiButtons.rowBand()`), and JEI's real button positions are read by reflection (JEI classes named `...Button`, log line `JEI's buttons in the bottom left found at ...`), so the "show bookmarks" click and this addon's own N button follow them wherever JEI puts them; without that the fixed bottom-left layout is assumed. A plain item saved with a recipe shows the recipe picture only while Shift is held, whatever `bookmarkRecipeTooltips` says (that setting is for recipe entries). Fluid bookmarks and recipes that no longer exist stay in JEI's list. If JEI's list is not found one warning is logged (`Could not move JEI's bookmarks ...`) and JEI keeps its own list. JEI's lookup history and item list areas are also kept clear by the panel now (`JeiAreas.history()` / `list()`).
  - Data Dumps (Settings > Tools > Data Dumps): items, blocks, potion effects, potions, entities, enchantments, biomes and JEI's item list as CSV files in `<game folder>/neiaddon/dumps`.
  - `itemUntranslator` (off): while the game is not in English, JEI's search also finds items by their English name. Read when JEI loads, so a change applies after the next JEI reload (rejoin the world).
  - Not ported: NEI's two toggles "bookmark items with recipe" / "recipe bookmark with count" (a bookmarked recipe here is a group of its own and is always added once), the mob spawner / chunk overlays, mouse-wheel item transfer between slots (vanilla has it), "ignore potion overlap".
>>>>>>> Stashed changes
- JEI's own bookmark overlay is switched off (it would be a second panel); the panel also appears on JEI's recipe screen. If JEI's internals change and this fails, a warning is logged and JEI's overlay stays.

## Known limits
Only the item id is saved for bookmarks (enchantments/potion types are lost on restart). The panel needs room for at least one 18px column left of the GUI; on narrow windows it hides. The creative inventory is no longer excluded. Collapsed groups show the non-ingredient items that fit on one row (as NEI). Not implemented from NEI: drop chances / fluids in the chain maths (container items are in), the Shift/Ctrl amount display modes of bookmark slots, auto-generated favorites. JEI's own recipe-bookmark button (next to the heart) still writes into JEI's hidden bookmark list; use the heart / Shift+A instead. Dragging only works on container screens, not JEI's recipe screen. The text calculator has no leftover sharing between crafts and uses each ingredient slot's first alternative; the recipe tree uses the item that has a favorite recipe, else the first. Bookmarks are read from JEI internals by reflection (no public API exists); if a JEI update changes them the import returns nothing and the C key still works.

## License
Author: Iambda. Source: https://github.com/IambdaA/NEI-features-for-JEI

This addon is licensed under the GNU Lesser General Public License, version 3 or (at your option) any later version (`LICENSE.txt`). The recipe tree, the favorites, the crafting-chain maths (`neiaddon.chain`), the bookmark layout rules and the key behaviour are ports of GTNH NotEnoughItems (https://github.com/GTNewHorizons/NotEnoughItems, LGPL-3.0-or-later) by Chicken-Bones, GTNewHorizons and the NotEnoughItems contributors; they keep that license and credit.


## Panel behaviour notes

- **Hotkeys on Alt:** items (bookmark panel and JEI's item list) and groups show "Hold ALT for hotkeys"; holding Alt lists
  the keys that work, merged and sorted like NEI (`HotkeyTips.format`). Ctrl+C copies the item name, Ctrl+X the item id.
  Over an item of JEI's list: Shift+A bookmarks its favorite recipe, Shift+F saves its favorite recipe tree.
- **Not ported (no equivalent without NEI's server side or 1.7.10 systems):** Ctrl+D (OreDictionary),
  pull items (V), recipe collapsing (Ctrl+D copies item tags instead of OreDictionary names).
- **JEI's two buttons at the bottom left:** the bookmark button shows or hides this addon's panel (JEI's own bookmark list is
  replaced). The history button works as in JEI; while the history list is on (left side) the panel leaves room for it.
  The JEI tooltip text of the bookmark button is replaced through `assets/jei/lang/en_us.json`.
- **No green highlight:** JEI paints every drop target green when an item is only hovered; the drop handler turns that off, so
  there is no highlight while dragging an item from JEI's list onto the panel either.
- **Quantity field:** a number box with + / - above JEI's search box (NEI's ItemQuantityField). 0 shows "One stack". It is the
  count of Ctrl+A over a JEI list item and the step of Ctrl+Alt+scroll in the panel. Wheel over it: 1, Shift 10, Ctrl 64.
  Stored in the addon's client config (`itemQuantity`). Its place is a guess at JEI's layout; JEI is asked to keep its item
  grid out of it through the extra-areas handler.

## Autocrafting (Shift+C)

Ported from NEI's `AutoCraftingManager` / `RecipeChainIterator` (`chain/ChainIterator`, `chain/ChainAutocraft`, tested by
`tools/AutoCraftCheck.java`). Over a crafting-chain group handle it crafts the whole chain, over a recipe's result in the
panel just that recipe, over an item that has a favorite recipe one run of it. Ctrl (Ctrl+Shift+C) only crafts what the
inventory is missing; plain Shift+C crafts the wanted amount again. Rebind it under Controls ("Craft Items").

Unlike NEI (client-side clicks) each craft uses JEI's recipe transfer to fill the grid, then shift-clicks the result slot,
one step per client tick. So: it needs an open screen with a crafting grid (inventory 2x2 or crafting table), JEI must be on
the server in multiplayer, it crafts one run at a time (NEI batches up to 64), and recipes JEI cannot transfer (furnaces,
machines) are skipped. It stops when the screen closes or the result will not move (inventory full). Not verified in game.

## Chat links (Ctrl+L)

Ported from NEI's `BookmarkPayload` / `NEIServerUtils.sendChatItemLink` / `CommandBookmarkAdd` (`Share`, `ShareClient`,
`Bookmarks.share*` / `importShared`). Ctrl+L over a group handle sends the group, over an item sends the item (amount =
the list quantity or the bookmark's amount), Ctrl+Shift+L sends a recipe (a panel recipe's result, the recipe under the
mouse on the JEI recipe screen, or a list item's favorite recipe). The client sends the payload to the server, which
broadcasts "<name> Click to add bookmark: [item]" and hands every client the payload; clicking the link is caught by the addon
(`ShareClient.onMousePressed`; Minecraft's own click did nothing here) and imports it. Typing `/neiaddon_bookmark` (a server
command, optionally with an id) imports too (groups keep how they were shown, a recipe that is already in the default group
is not added again, plain items add to the existing amount). The last 64 links are kept per client, like NEI.

**The addon must be installed on the server** (it is now a normal mod on both sides; the channel is optional, so players or
servers without it still connect, they just cannot share). Singleplayer works. Item links do not carry NEI's attached recipe
id (there is no such thing as a plain item with a recipe here). Not verified in game.

## Pull items (V)

Ported from NEI's `BookmarkPanel.pullBookmarkItems`, `DefaultBookmarkContainerHandler` and `FastTransferManager`
(`Pull`, the maths in `chain/ChainPull`, tested by `tools/PullCheck.java`). With a chest open: V over a group handle pulls
the items of the group (a crafting chain pulls the raw materials it needs that the chest holds, the chest's items being
used before anything is made), V over a bookmark pulls it at its amount, V over any other item (list, inventory, recipe
screen) pulls the quantity field's amount of it. Shift+V only pulls what the inventory is missing. The "P" button right
of the bookmark page arrows pulls everything of the ungrouped items.

Like NEI it only works in a chest (`ContainerScreen`; the last 36 menu slots are the inventory). Whole stacks go with one
shift-click, parts are picked up and placed one by one into the slot a shift-click would use (NEI does it with three clicks
per item). Items are matched by registry id. Not verified in game.


## Collapsed recipes, tag slots, collapsing the ungrouped items

- Collapsed recipes and recipe relations are a port of NEI's RecipeChainDetails / BookmarkGridGenerator branch for collapsed recipes (ChainDetails, BookmarkPanel.buildCells, Bookmarks.toggleCollapsedRecipe/removeSlot). Items of merged slots sort by item id (NEI uses its configurable ItemSorter). Dragging, Shift+Remove and Ctrl+scroll treat the collapsed recipe and the recipes it swallows as one. Saved per group in bookmarks.txt and in chat links.
- Not checked in game: all of this was only compiled against stand-in classes and run through the layout/maths checks (tools/LayoutCheck, CollapseCheck).


## Crafting tree (T over a crafting-chain group, or Shift+T over a recipe heart)

Port of NEI's `GuiCraftingTree`. The pure parts are in `chain/` (`TreeMath`, `TreeGraph`, `TreeLayout`, `TreeCamera`,
`TreeStats`, `TreeStatsLayout`, `ReadableNumber`, tested by `tools/TreeCheck.java`); the window is `CraftingTreeScreen`
(with `TreeDraw` for the texture pieces, `TreeState` for the settings and the per-group view, `TreeRecipes` for the
"+" button in JEI's recipe screen).

- Drag pans, wheel scrolls (Shift sideways), Ctrl+wheel zooms (config `treeZoomWithoutCtrl` = zoom without Ctrl).
- Click an item: recipes (LMB) / usages (RMB), R and U too. A click on a node with children remembers it; the "+" of the
  next recipe you pick in JEI replaces that node's recipes with it.
- Alt+click collapses/expands the recipe, click on the little arrow folds the branch, the badge shows the recipe type
  and turns into a delete button (removes the recipe and the recipes only it needed).
- Ctrl+wheel over a top item changes its amount (Ctrl+Alt+wheel by the quantity field's step), Shift+wheel over a tag
  slot switches the item.
- Toolbar: inventory snapshot, fit to view, collapse mode (expanded / collapse resolved / collapse all), stats panel,
  stats sections, send group to chat. Settings are saved in `config/neiaddon/crafting_tree.properties`
  (NEI keeps them in the world data).
- Stats panel: ingredients, crafting, results, handlers; click a slot to highlight it in the tree (Shift = previous,
  RMB clears).
- Search box: words must be in the item's name or id, `@mod`, `$tag`. NEI's search language is NOT ported.
- Not ported: drop chances, fluids. Container items (a bucket left behind, a tool that comes back worn) are in the chain maths and tree: a tool node shows its amount as `~n` in orange, and what is left behind is listed under Remainders in the stats panel. GregTech tool stats are not read.
- Not checked in game: only compiled against stand-in classes and run through the maths checks. Risks: item and texture
  drawing under the pose scale, the second JEI button factory, the inventory snapshot read from the open menu, the
  recipe picture in the badge tooltip.
