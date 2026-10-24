# NEI Features for JEI (NeoForge 26.2) - build notes

Status: an early version built and loaded in game. Everything added since (NEI-style bookmark panel, highlight tips, info pages, the recipe tree) has NOT been compiled against the real game: the authoring environment cannot download Minecraft/NeoForge/Gradle, so only this addon's own logic was checked (see `tools/`). Expect a few compile errors on the first build and send back any lines starting with `error:`.

## Build
1. JDK 25. 2. `./gradlew clean build` (Windows: `gradlew.bat clean build`). 3. `./gradlew runClient` starts a dev client with JEI included.
`neo_version` (gradle.properties) came from the official 26.2 template; `jei_version` is 30.42.0.237. Change either if the build can't resolve them.
Send back any lines starting with `error:`.

## This is the Minecraft 1.21.1 build
Same features as the 26.x builds, converted to the 1.21.1 / NeoForge 21.1 APIs (JEI 19.x). Build: JDK 21, `./gradlew clean build`.
What differs from the 26.x code: drawing uses `GuiGraphics` (1.21.1 draws with a depth test, so everything drawn in front of an
item goes through `Pose.over` / `Pose.begin`), screens use the old `render` / `mouseClicked(double, double, int)` methods, ids are
`ResourceLocation`s, chat link click and hover events are the 1.21.1 ones, and the mod-bus events are in `ClientModEvents` and
`CommonModEvents`. The text calculator window (not reachable since the tree replaced it) is not part of this build.
Not tried in game yet: expect a few compile errors on the first build and some draw order problems.

## Features
- **Highlight tips** (as NEI's HUDRenderer): a tooltip-style box with the item and name of the BLOCK under the crosshair (not entities). Off by default; Numpad 0 toggles it, the player-list key (Tab) hides it. Position `hudX`/`hudY` (0..10000, NEI defaults 5000/100) in `neiaddon-client.toml`; bind "Move Highlight Tooltips" in Controls to open NEI's drag-to-position screen. Extras not in NEI (off by default): `hudShowMod`, `hudShowId`.
- **Item info pages**: `config/neiaddon/informationpages.cfg` (created on first start), NEI's `filter = text` format. Filters: `namespace:path` (with `*`), `$namespace:tag` (NEI's `$oredict`; `$name` means `c:name`), `@modid`. Lines starting with `#` are comments, as in NEI. Shown in JEI on the item's recipe page.
- **Bookmarks (modelled on GTNH NotEnoughItems' BookmarkPanel)**: a grid of item slots on the left of container screens and JEI's recipe screen. Drag items in from JEI's list.
  - Group strip (7px, left of the slots): drag with the left button to create a group or extend the one you start in, right button to exclude rows. A plain click on a group: LMB = toggle group mode (todo list: one item per row), RMB = toggle crafting chain (green bracket), Alt+LMB = collapse. Shift+LMB drag = move the group. Ctrl+scroll = change the quantity of the whole group (Ctrl+Alt = multiplier 64).
  - Slot: LMB = recipes, RMB = uses, Shift+LMB drag = move (a recipe item moves its whole recipe), Ctrl+scroll = amount (Ctrl+Alt = stack size; on a recipe item it changes how often the whole recipe runs), Alt+LMB re-expands a collapsed group; in a crafting chain it collapses/expands the slot's recipe into one merged block (the recipe's results, then what it and the recipes it feeds on need; boxed in purple). Shift+scroll on an ingredient that accepts several items (tag) switches the item. Alt+LMB on the page label collapses the ungrouped items. Amounts are drawn at half size bottom right (only when above 0).
  - Top `< 1/2 >`: grid pages (scroll too). The label is the handle of the ungrouped items: LMB = todo mode, RMB = crafting chain, Ctrl+scroll = quantity. The brackets turn green when the chain is on.
  - Bottom `< 1/3 >`: bookmark pages ("namespaces"). Like NEI there is no add button: an empty page always follows the last used one, and empty pages vanish when you leave them. Scroll over the buttons to switch. Saved in `config/neiaddon/bookmarks.txt`.
- **Recipe tree (favorites + recipe bookmarks, as NEI)**: every recipe in JEI's recipe screen has a heart button next to JEI's own buttons (added through JEI's `addRecipeButtonFactory`, so no mixin).
  - Click the heart: this recipe becomes the favorite recipe of its result item (one favorite per item, red heart = favorite; the wheel over the heart picks the result of a multi-result recipe). Saved in `config/neiaddon/favorites.txt` (`itemId <tab> recipeId`).
  - `Shift+A` while the mouse is over a heart: saves the recipe tree (the recipe, the favorite recipe of each ingredient, and so on, depth 9, nothing is searched automatically) as ONE new bookmark group in todo-list view with the crafting chain on. `Shift+T` over a heart saves it and opens the calculator for that group. `Shift+A` over a recipe: bookmarks / un-bookmarks just that recipe (only the hovered result) in the ungrouped items.
  - Todo-list view: one recipe per row (result first, then its ingredients; ingredients that do not fit continue from the second column of the next row). In a crafting-chain group the amounts are calculated by the ported NEI chain maths (how often every sub-recipe has to run so the top recipe gets everything it needs; your own plain items are spent first); the first result of an output recipe has a small `xN` marker (N = its multiplier). Normal view of a chain group shows only results and plain items. `Ctrl+wheel` on a recipe item changes how often the whole recipe runs (`Ctrl+Alt` = stack size steps), `Ctrl+wheel` on the group strip scales the whole group, `A` over a recipe item removes it (a recipe's only result removes the whole recipe), `Shift+A` removes the whole recipe, RMB on the strip toggles the crafting chain. Hovering the strip of a chain group lists the raw materials it still needs.
  - Recipe ids: JEI's recipe identifier (`recipeTypeUid|registry id`), or for recipes without one a hash of the category and slots (`recipeTypeUid#hash`). Bookmark file lines: `item <id> <amount> <group> <type 0/1/2> <recipeId|-> <factor> <option ids>`, group flags in `meta` lines. Lines of uninstalled items are kept untouched and shown as nothing.
  - The chain maths is plain Java (`neiaddon.chain`, no Minecraft classes) and is checked by `tools/ChainMathCheck.java`; the model and the layout by `tools/ModelCheck.java` / `tools/LayoutCheck.java` against stand-in classes (`tools/make_stubs.py`, see the headers of those files). These checks never touch the real game: they only catch mistakes in this addon's own code.
- **Crafting calculator** (per group): steps and raw materials; click a step = other recipe, right click = treat as raw, +/- adjust amounts. Works with any recipe type JEI knows.
- **Keys (Controls, NEI defaults)**: `A` toggles a bookmark for the hovered item (JEI list, recipe screen or inventory slot; over a bookmark it removes it; Ctrl+A keeps the stack size), `Shift+A` over a group strip or the page label removes the group, `T` over a group strip or the label opens the crafting tree window when that group is in crafting-chain mode, `B` hides/shows the panel. `R`/`U` are JEI's own keys and already match NEI. Ignored while the JEI search box has focus.
- JEI's own bookmark overlay is switched off (it would be a second panel); the panel also appears on JEI's recipe screen. If JEI's internals change and this fails, a warning is logged and JEI's overlay stays.

## Known limits
Only the item id is saved for bookmarks (enchantments/potion types are lost on restart). The panel needs room for at least one 18px column left of the GUI; on narrow windows it hides. The creative inventory is no longer excluded. Collapsed groups show the non-ingredient items that fit on one row (as NEI). Not implemented from NEI: the crafting tree's screenshot button, container items / drop chances / fluids in the chain maths, the Shift/Ctrl amount display modes of bookmark slots, auto-generated favorites. JEI's own recipe-bookmark button (next to the heart) still writes into JEI's hidden bookmark list; use the heart / Shift+A instead. Dragging only works on container screens, not JEI's recipe screen. The text calculator has no leftover sharing between crafts and uses each ingredient slot's first alternative; the recipe tree uses the item that has a favorite recipe, else the first. Bookmarks are read from JEI internals by reflection (no public API exists); if a JEI update changes them the import returns nothing and the C key still works.

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
- Not ported: the screenshot button, container items, drop chances, fluids.
- Not checked in game: only compiled against stand-in classes and run through the maths checks. Risks: item and texture
  drawing under the pose scale, the second JEI button factory, the inventory snapshot read from the open menu, the
  recipe picture in the badge tooltip.
