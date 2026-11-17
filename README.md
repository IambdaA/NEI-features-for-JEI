# NEI Features for JEI

An unofficial port of features from [GTNH NotEnoughItems](https://github.com/GTNewHorizons/NotEnoughItems) to
[Just Enough Items](https://github.com/mezz/JustEnoughItems), for **Minecraft 26.1.2 on NeoForge and on Fabric** (one project, two jars). Not affiliated with the
NotEnoughItems authors.

**Download:** [CurseForge](https://www.curseforge.com/minecraft/mc-mods/nei-features-for-jei)

## Features

### From NEI

Ported from [GTNH NotEnoughItems](https://github.com/GTNewHorizons/NotEnoughItems):

- Bookmark panel with groups, todo-list and crafting-chain views, collapsed recipes, tag slots and multiple pages
- Crafting chains: how often every recipe has to run, crafting tools counted by the durability they need
- Fluid slots of JEI recipes (in millibuckets) in bookmarks, the chain maths and the crafting tree (first version)
- Favorite recipes and recipe trees (heart button in JEI's recipe screen)
- Crafting tree window with a stats panel (zoom, pan, collapse, pick another recipe for an item, inventory snapshot,
  search, save the whole tree as one PNG)
- Autocrafting (Shift+C), pulling items from chests (V), chat links for items, recipes and groups (Ctrl+L)
- Cheat mode, utility buttons (time of day, rain, heal, game mode) and the quantity field
- NEI recipe search: a field under JEI's recipe screen that filters the recipes shown (query like `<iron >rail`), and
  the same search syntax in the crafting tree search
- Recipe screen extras: all items of a cycling slot in one grid (middle click), fill the crafting grid with a recipe
  (S), highlight of the ingredients you already have, the favorite recipe as a picture beside item tooltips, favorites
  generated for items with a single recipe
- JEI's item list: subsets (S button), collapsible item groups, English-name search (Item Untranslator), copy an
  item's name, tags or id
- Item zoom (Shift+Z), craftables panel (off by default), highlight tips HUD, item info pages, data dumps
- NEI's options screen and many of its settings (a few defaults differ from NEI's, see BUILDING.md)

### Added by this mod

Not in NEI:

- Crafting-chain tooltip that counts your inventory: hold Shift for the missing items in red and the crafts still
  needed, Shift+Ctrl to plan only what is missing
- Crafting mode in the crafting tree: it counts your inventory live, and storage you open tints the items the tree
  still needs (orange; Highlight Chain Items in the settings)
- G in cheat mode gives everything a crafting chain is missing, tools included
- Autocrafting in AE2 crafting terminals (from the network), Tinkers' Crafting Station and Sophisticated Storage's
  crafting upgrade
- AE2 and Refined Storage: bookmarks and tooltips show how many the network holds; T types an item into the AE2
  terminal's search
- JEI's own bookmark button and key fill the bookmark panel; JEI's settings on one page in the addon's settings
- The bookmark panel moves out of the way of other mods' buttons (FTB mods, Curios and more)

<details>
<summary><b>Full feature guide</b> (click to expand)</summary>

Every key below is the default and can be changed under **Controls > NEI Features for JEI**. Hold **Alt** over any item
or bookmark group to see the keys that work on it right there.

### Settings

- Open them with the **N** button in JEI's bottom-left button row, or the mod's Config button in the mod list.
- **Ctrl + click** on N cycles between Recipe Mode, Utility Mode and Cheat Mode (see [Cheat mode](#cheat-mode)).
- The settings are laid out like NEI's options screen: **Inventory** holds most options and the sub-pages (Bookmarks
  Panel, Recipes GUI, History Panel, Craftables Panel, Item Zoom Panel, Favorites); **World** holds Highlight Tooltips;
  **Tools** holds Data Dumps. **JEI Options** has JEI's own settings on one page in the same style, with a title row for each JEI category (in a world only), and **Report Issues** opens the issue tracker.
- Settings are saved in `config/neiaddon-client.toml`.

### Bookmark panel

A grid of bookmarked items on the left of inventory screens and JEI's recipe screen.

**Adding and removing**
- **A** over any item (JEI's list, a recipe, your inventory, the panel) adds it, or removes it if it is already there.
  **Ctrl + A** adds it with a count (the Stack Controls amount, or the stack size).
- **A** over an item inside a recipe saves that recipe with it (setting: Bookmark Items With Recipe); its tooltip then
  shows the recipe. The same item from two different recipes is two bookmarks.
- **A** over a recipe's *output* bookmarks the recipe instead, when JEI's own "Bookmark Output as Recipe" option is on:
  the output item with the recipe type's icon in its corner, the recipe shown while Shift is held.
- Drag items from JEI's list onto the panel.
- Drag a bookmark onto another mod's filter or ghost slot to set that slot to it.
- JEI's own bookmark button and key fill this panel too (setting: JEI's Bookmark Button Fills This Panel).
- Fluids can be bookmarked (one bucket; Ctrl keeps the slot's amount).
- **B** shows or hides the panel; so does JEI's bookmark button. While you type in a search field (JEI's, AE2's, the
  creative inventory's...), the addon's keys do nothing, so B and the other letters just type.

**Using bookmarks**
- **Left click / R**: recipes for the item. On a bookmarked recipe, JEI opens on that recipe first.
- **Right click / U**: what the item is used in.
- **Shift + drag**: move a bookmark (a recipe moves as a whole).
- **Ctrl + scroll**: change the amount. **Ctrl + Alt + scroll**: change it in steps of the Item Quantity (one stack by default).
- **Shift + scroll** on an ingredient that accepts several items (a tag) switches the item.

**Groups** - the thin strip left of the slots:
- **Drag with left click** over rows to make a group or extend one; **drag with right click** to take rows out.
- **Left click** the strip: toggle todo-list view (one recipe per row). **Right click**: toggle crafting chain (green
  bracket, see [Crafting chains](#crafting-chains)). **Alt + left click**: collapse or expand the group.
- **Shift + drag** the strip moves the whole group. **Ctrl + scroll** scales every amount in it.
- **Shift + A** over the strip removes the group.
- The page label at the top is the handle of the ungrouped bookmarks and takes the same clicks.

**Pages**
- The arrows at the top flip grid pages; the arrows at the bottom switch bookmark lists. A new empty list always
  follows the last one, so you can keep separate lists per project.
- Bookmarks are saved in `config/neiaddon/bookmarks.txt` (setting World Specific: one list per world or server).

**Look**
- Bookmark Scale (50-100 %) makes the slots smaller so more fits on a page.
- Show Recipe Marker: mark recipe results with an R, the recipe type's icon (Show Handler Icon, size with Handler Icon
  Scale) or a tinted background.
- Panel Margin Top / Left move the panel if it overlaps something another mod draws. The panel already moves out of
  the way of other mods' buttons by itself.

### Favorite recipes and recipe trees

- Every recipe in JEI's recipe screen gets a **heart** button. Click it to make that recipe the item's favorite (red
  heart). Hold **Alt** over it to see its keys. Scroll over the heart to pick which result it is for, or press **Shift + F** over a result.
- **Shift + A** over a heart saves the whole recipe tree (the recipe, the favorite recipe of each ingredient, and so on,
  up to the Depth setting) as a new bookmark group with the crafting chain on. **Shift + A** over a recipe saves just
  that recipe.
- **T** (or **Shift + T**) over a heart builds the tree from the current favorites and opens it in the crafting tree.
  If the page already has a tree group whose top recipe is this recipe, that group is refreshed in place (same place,
  its top recipe keeps its run count); otherwise the tree is saved as a new group.
- Over an item in JEI's list: **Shift + A** bookmarks its favorite recipe, **Shift + F** saves its favorite recipe tree.
- Generate Single-Recipe Favorites makes an item with only one recipe use it as its favorite.
- Show Recipe Tooltips in Right Panel / Recipe Gui: an item's favorite recipe as a picture beside its tooltip.

### Crafting chains

Turn a group into a crafting chain with a right click on its strip. The mod then works out how often every recipe in the
group has to run so the end result gets everything it needs, using what you already have first.

- The first result of each recipe shows **xN**: how often it runs.
- Hover the strip for the chain's tooltip: results, required crafts and ingredients. **Hold Shift** to count your
  inventory: missing ingredients turn red. **Shift + Ctrl** plans only what is still missing (like Ctrl + Shift + C).
- Crafting tools (GregTech hammers, files...) are listed by the **durability** the chain needs, in blue, and turn red
  when all the tools of that kind in your inventory together don't have enough. A worn tool counts as the same tool.
- **Alt + click** a recipe in a chain to collapse it into one block.
- Storage you open tints the items the crafting trees in crafting mode on this page still need (orange); setting **Highlight Chain Items**.

### Crafting tree

**T** over a crafting chain's strip opens it as a tree (or **T** over a recipe's heart, see Favorite recipes).
- Scroll to zoom (setting: Tree Zoom without Ctrl), drag to pan, **Alt + click** an item to collapse or expand its
  recipe. Ctrl + scroll changes amounts, as in the panel. Hold Alt for the keys.
- **Pick a recipe for an item**: click the item (left click for recipes, right click for usages). JEI's recipe screen
  opens; click the **heart** of the recipe you want. That recipe replaces the clicked item's recipe in the tree, and the
  tree opens again. The recipe becomes the favorite of the item you looked up for recipes (for a usages lookup, the item
  of the replaced node, if any; else the heart's selected result when the recipe does not make that item). The click
  never turns a favorite off: it replaces that item's earlier favorite, and other items lose this recipe as their
  favorite, as with any favorite.
- The crafting table button turns on **crafting mode**: the tree counts your inventory live (about once a second), so the
  have and still-needed numbers and the greyed items follow what you carry, and storage you open is tinted for what the tree still needs.
- The toolbar can use an inventory snapshot, fit the tree to the view, collapse finished items, collapse or expand all,
  send the group to chat, and **save the whole tree as an image** in the screenshots folder.
- The stats panel lists handlers, ingredients and results; click a handler to highlight its recipes.
- The search box finds items in the tree.

### Autocrafting

Open a crafting table (or your inventory for 2x2 recipes) and press **Shift + C**:
- over a crafting chain's strip: crafts the whole chain;
- over a recipe's result in the panel: crafts that recipe;
- over an item with a favorite recipe: crafts it once.

**Ctrl + Shift + C** only crafts what your inventory is missing. Plain Shift + C crafts the full amount again.

- It moves the ingredients with JEI's recipe transfer and crafts as many sets at once as fit. JEI must be installed on
  the server in multiplayer.
- It works at any crafting table JEI can fill, such as Tinkers' Crafting Station and Sophisticated Storage's crafting
  upgrade (not while the upgrade refills its own grid). Leftover ingredients go back into the table's own storage
  (a crafting station's side chest, the Sophisticated storage) when they came from there, and so do the items made
  on the way. The crafted items and your own tools end up in your inventory.
- It works in an AE2 crafting terminal too (not tested on 26.1 yet): the network's items count as yours, the grid is filled
  from the network, and what is left in the grid goes back into the network at the end (chat says so when it couldn't). A recipe whose
  ingredients are not all in your inventory, the network or the grid is skipped before anything is moved; a recipe
  whose result comes out as another item is skipped too, its ingredients go back into the network, and the end of the
  run says what did not reach the grid. What the chain makes on the way goes into the network (AE2 refills the
  grid from there; if the network refuses it, it comes to your inventory), and the items you asked for come to your
  inventory; anything that doesn't fit there goes into the network too. A recipe whose tool isn't made yet waits for
  it.
- Recipes with a tool are filled up around the tool and crafted in one go; a broken tool is replaced from your inventory.
- When it stops, or finishes without making something, chat says why.
- Settings: Faster Autocrafting (crafts the exact amount from a full grid), Restore Inventory Layout (puts your items
  back in their old slots afterwards).
- Recipes JEI can't move into a crafting grid (furnaces, machines) are skipped.

### Cheat mode

Turn it on in Settings > Inventory (Cheat Mode), with **Ctrl + click** on the N button, or use JEI's own cheat mode.
Giving goes through JEI on the server, so the server still decides whether you may cheat.

- **Left click** an item in JEI's list: gives the Stack Controls amount (0 = one stack). **Right click**: gives one.
  **Shift + click** still shows recipes.
- **Left click** a bookmark: gives its own amount (or the Stack Controls amount). **Right click**: gives one. Fluid
  bookmarks give filled buckets.
- With an item on the cursor over JEI's list or the bookmark panel: **left click deletes it**, right click removes one.
- **G** over a crafting chain's strip gives everything the chain is missing (the red ingredients of its Shift tooltip),
  including enough tools to cover the durability it needs. Also works in creative mode.
- **Utility buttons** above the panel: time of day, rain, heal and feed, game mode. Utility Mode shows the ones you
  pick under Utilities; Cheat Mode shows all. The same actions can be bound to keys (unbound by default).

### Stack Controls

The number box with + / - above JEI's search box. It sets the amount for Ctrl + A, cheat-mode clicks and the
Ctrl + Alt + scroll step. Type a number, click + / -, or scroll over it (Shift: 10, Ctrl: 64).

### JEI's item list

- **Subsets**: the **S** button next to N lists every mod (with item counts) and every creative tab. Picking one filters
  JEI's list; it combines with whatever you type.
- **Collapsible item groups** (off by default, Settings > Inventory): families of items like wool, concrete, dyes,
  spawn eggs and potions show as one entry with a green +. **Alt + click** opens or closes a group; groups open while
  you search. Edit or add groups in `config/neiaddon/collapsibleitems.cfg` with item tags (`#minecraft:wool`), mods
  (`@ae2`) or item ids with `*` (`*:*_spawn_egg`).
- **Item Untranslator**: when the game is not in English, JEI's search also finds items by their English name.
- **Ctrl + C / Ctrl + D / Ctrl + X** over an item copy its name, tags or id.
- **Ctrl + L** sends the item to chat as a link others can click to bookmark it; **Ctrl + Shift + L** sends a recipe,
  and over a group strip the whole group. Needs the mod on the server.

### JEI's recipe screen

- **Middle click** a slot that cycles through several items (like "any planks") to see all of them in a grid. Left
  click or R shows an item's recipes, right click or U its uses.
- **S** over a recipe moves its ingredients into the open crafting grid (**Shift + S**: as many as fit).
- **Recipe Search Field** (Recipes GUI): a search box under the recipe screen that filters the recipes shown.
  `<iron` has iron as an ingredient, `>rail` makes a rail, `^` crafting station, `|` or, `-` not, `@mod`, `#tooltip`,
  `$tag`, `&id`.
- **Highlight Present Item**: tints the ingredients you already have.

### Storage and chests

- **Storage counts**: with an AE2 terminal or a Refined Storage 2 grid open, bookmarks show how many the network holds
  (cyan, top left) and item tooltips get an "In storage" line.
- **T** over an item while an AE2 terminal is open types its name into the terminal's search.
- **Pull items** (chest open): **V** over a group's strip pulls its items (a crafting chain pulls the raw materials it
  needs), over a bookmark pulls it at its amount, over any other item pulls the Stack Controls amount. **Shift + V** only
  pulls what you are missing. The **P** button next to the bookmark page arrows pulls all ungrouped bookmarks.

### Other panels and tools

- **Item zoom**: the hovered item drawn large beside the inventory. **Shift + Z** toggles it; zoom in, zoom out and
  hold-to-show keys can be bound. Options in Settings > Item Zoom Panel.
- **Craftables panel** (off by default): what the open container can craft from your inventory, above the Stack
  Controls. Shift + C over an entry crafts it.
- **Highlight tips**: the block under your crosshair in a tooltip-style box. **Numpad 0** toggles it; bind "Move Highlight
  Tooltips" to drag it into place.
- **Item info pages**: write extra info pages for items in `config/neiaddon/informationpages.cfg` (`filter = text`;
  filters are item ids with `*`, `$tag` or `@mod`). They show up in JEI on the item's recipe page.
- **Data dumps** (Settings > Tools): items, blocks, potion effects, potions, entities, enchantments, biomes and JEI's
  item list as CSV files in `neiaddon/dumps`.

### Compatibility notes

- The bookmark panel moves around buttons and panels other mods put on the inventory screen (FTB mods, Curios, Apothic
  Attributes and more). If something still overlaps, use Panel Margin Top / Left.
- NTM - Continuum fluids show once in JEI's list.
- JEI++ changes the same things this mod does; a chat message warns when it is installed.

</details>

Technical notes for each feature are in [BUILDING.md](BUILDING.md).

## Requirements

- Minecraft 26.1.2 only, with NeoForge 26.1.2.x (built against 26.1.2.122), or Fabric (loader 0.19+ and Fabric API 0.155+)
- JEI 29.x

## Status

Early version. Parts have been tested in game, but not every feature in every setup. Bug reports are welcome in the
[issue tracker](../../issues).

Not ported from NEI: NC/chance badges (JEI has no API for them), drop chances in the
chain maths (JEI gives no chance data on recipe slots).

## Building

JDK 25, then `./gradlew build`. The jars are `neoforge/build/libs/neiaddon-neoforge-26.1.2-*.jar` and `fabric/build/libs/neiaddon-fabric-26.1.2-*.jar`.
`./gradlew :neoforge:runClient` or `./gradlew :fabric:runClient` starts a dev client with JEI. The shared code is in `common/`.

## License

LGPL-3.0-or-later, see [LICENSE.txt](LICENSE.txt). Large parts are ports of GTNH NotEnoughItems (LGPL-3.0-or-later) by
Chicken-Bones, GTNewHorizons and the NotEnoughItems contributors, and keep that license and credit. The crafting tree
textures (`craftingtree.png`, `nei_sprites.png`) are from NotEnoughItems too.
