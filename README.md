# NEI Features for JEI

An unofficial port of features from [GTNH NotEnoughItems](https://github.com/GTNewHorizons/NotEnoughItems) to
[Just Enough Items](https://github.com/mezz/JustEnoughItems), for **Minecraft 26.1.2 on NeoForge and on Fabric** (one project, two jars). Not affiliated with the
NotEnoughItems authors.

## Features

- Bookmark panel with groups, todo-list and crafting-chain views, collapsed recipes, tag slots and multiple pages
- Favorite recipes and recipe trees (heart button in JEI's recipe screen)
- Crafting tree window with a stats panel (zoom, pan, collapse, inventory snapshot, search)
- Autocrafting (Shift+C), pulling items from chests (V), chat links (Ctrl+L)
- Crafting tree toolbar button "Save tree as image": the whole tree as one PNG in the screenshots folder
- Crafting-chain group tooltip: hold Shift to count your inventory (missing items in red, required crafts), Ctrl to keep the amounts as they are
- Item zoom: the hovered item drawn large beside the inventory (Shift+Z toggles, NEI options)
- NEI recipe search: a field under JEI's recipe screen that filters the recipes shown (query like `<iron >rail`), and the same search syntax in the crafting tree search
- Craftables panel (off by default, `craftablesEnabled` in the config): what the open container can craft from your inventory, above the quantity field
- Recipe screen extras: fill the crafting grid with a recipe (S), highlight of the ingredients you already have, the favorite recipe as a picture beside item tooltips, favorites generated for items with a single recipe
- Utility buttons above the bookmark panel (time of day, rain, heal, game mode; NEI's Cheat Mode setting) and the same as keys, data dumps, English-name search, many NEI settings (a few defaults differ from NEI's, see BUILDING.md)
- Fluid slots of JEI recipes (in millibuckets) in bookmarks, the chain maths and the crafting tree (first version)
- Highlight tips HUD and item info pages
- Crafting calculator

<details>
<summary><b>Full feature guide</b> (click to expand)</summary>

Every key below is the default and can be changed under **Controls > NEI Features for JEI**. Hold **Alt** over any item
or bookmark group to see the keys that work on it right there.

### Settings

- Open them with the **N** button in JEI's bottom-left button row, or the mod's Config button in the mod list.
- **Ctrl + click** on N cycles between Recipe Mode, Utility Mode and Cheat Mode (see [Cheat mode](#cheat-mode)).
- The settings are laid out like NEI's options screen: **Inventory** holds most options and the sub-pages (Bookmarks
  Panel, Recipes GUI, History Panel, Craftables Panel, Item Zoom Panel, Favorites); **World** holds Highlight Tooltips;
  **Tools** holds Data Dumps. **JEI Options** opens JEI's own settings and **Report Issues** opens the issue tracker.
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
- JEI's own bookmark button and key fill this panel too (setting: JEI's Bookmark Button Fills This Panel).
- Fluids can be bookmarked (one bucket; Ctrl keeps the slot's amount).
- **B** shows or hides the panel; so does JEI's bookmark button.

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
  heart). Scroll over the heart to pick which result it is for, or press **Shift + F** over a result.
- **Shift + A** over a heart saves the whole recipe tree (the recipe, the favorite recipe of each ingredient, and so on,
  up to the Depth setting) as a new bookmark group with the crafting chain on. **Shift + T** does the same and opens the
  crafting tree. **Shift + A** over a recipe saves just that recipe.
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

### Crafting tree

**T** over a crafting chain's strip opens it as a tree (or **Shift + T** over a heart).
- Scroll to zoom (setting: Tree Zoom without Ctrl), drag to pan, **Alt + click** an item to collapse or expand its
  recipe. Ctrl + scroll changes amounts, as in the panel. Hold Alt for the keys.
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
- It works at any crafting table JEI can fill, such as Tinkers' Crafting Station.
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
- **Highlight tips**: the block under your crosshair, shown NEI style. **Numpad 0** toggles it; bind "Move Highlight
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

## Versions

| Branch | Minecraft | Loader | JEI | Status |
| --- | --- | --- | --- | --- |
| `26.2` | 26.2 | NeoForge, Fabric | 30.x | runs in game on both |
| `26.1.2` | 26.1.2 only | NeoForge, Fabric | 29.x | runs in game on both |
| `1.21.1` | 1.21.1 | NeoForge, Fabric | 19.x | runs in game on both |
| `1.20.1` | 1.20.1 | Forge, Fabric | 15.20+ | runs in game on both |

Each branch builds with its own `gradle.properties`; see BUILDING.md. Releases are tagged by Minecraft version, for example `v0.1.0+mc1.21.1`.
