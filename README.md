# NEI Features for JEI

An unofficial port of features from [GTNH NotEnoughItems](https://github.com/GTNewHorizons/NotEnoughItems) to
[Just Enough Items](https://github.com/mezz/JustEnoughItems), for **Minecraft 26.1, 26.1.1 and 26.1.2 / NeoForge**. Not affiliated with the
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
- Highlight tips HUD and item info pages
- Crafting calculator

See [BUILDING.md](BUILDING.md) for the full list, keys and notes.

## Requirements

- Minecraft 26.1, 26.1.1 or 26.1.2, NeoForge 26.1.x, JEI 29.x (not tested in game yet)

## Status

Early version. Parts have been tested in game, but not every feature in every setup. Bug reports are welcome in the
[issue tracker](../../issues).

Not ported from NEI: collapsible item groups and NC/chance badges (JEI has no API for either), drop chances and fluids in the
chain maths (JEI gives no chance data on recipe slots, and the addon only knows items).

## Building

JDK 25, then `./gradlew build`. The jar is in `build/libs/`. `./gradlew runClient` starts a dev client with JEI.

## License

LGPL-3.0-or-later, see [LICENSE.txt](LICENSE.txt). Large parts are ports of GTNH NotEnoughItems (LGPL-3.0-or-later) by
Chicken-Bones, GTNewHorizons and the NotEnoughItems contributors, and keep that license and credit. The crafting tree
texture (`craftingtree.png`) is from NotEnoughItems too.

## Versions

| Branch | Minecraft | Loader | JEI | Status |
| --- | --- | --- | --- | --- |
| `26.1.x` (this branch) | 26.1, 26.1.1, 26.1.2 | NeoForge | 29.x | one jar, not tested in game yet |
| `1.21.1` | 1.21.1 | NeoForge | 19.x | works in game |
| `1.20.1` | 1.20.1 | Forge | 15.38+ | launches, still being tested |

Each branch builds with its own `gradle.properties`; see BUILDING.md. Releases are tagged by Minecraft version, for example `v0.1.0+mc1.21.1`.
