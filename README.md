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
- Fluid slots of JEI recipes (in millibuckets) in bookmarks, the chain maths and the crafting tree (first version)
- Highlight tips HUD and item info pages
- Crafting calculator

See [BUILDING.md](BUILDING.md) for the full list, keys and notes.

## Requirements

- Minecraft 26.1.2 only, with NeoForge 26.1.2.x (built against 26.1.2.122), or Fabric (loader 0.19+ and Fabric API 0.155+)
- JEI 29.x

## Status

Early version. Parts have been tested in game, but not every feature in every setup. Bug reports are welcome in the
[issue tracker](../../issues).

Not ported from NEI: collapsible item groups and NC/chance badges (JEI has no API for either), drop chances in the
chain maths (JEI gives no chance data on recipe slots).

## Building

JDK 25, then `./gradlew build`. The jars are `neoforge/build/libs/neiaddon-neoforge-26.1.2-*.jar` and `fabric/build/libs/neiaddon-fabric-26.1.2-*.jar`.
`./gradlew :neoforge:runClient` or `./gradlew :fabric:runClient` starts a dev client with JEI. The shared code is in `common/`.

## License

LGPL-3.0-or-later, see [LICENSE.txt](LICENSE.txt). Large parts are ports of GTNH NotEnoughItems (LGPL-3.0-or-later) by
Chicken-Bones, GTNewHorizons and the NotEnoughItems contributors, and keep that license and credit. The crafting tree
texture (`craftingtree.png`) is from NotEnoughItems too.

## Versions

| Branch | Minecraft | Loader | JEI | Status |
| --- | --- | --- | --- | --- |
| `26.2` | 26.2 | NeoForge, Fabric | 30.x | runs in game on both |
| `26.1.2` | 26.1.2 only | NeoForge, Fabric | 29.x | runs in game on both |
| `1.21.1` | 1.21.1 | NeoForge, Fabric | 19.x | runs in game on both |
| `1.20.1` | 1.20.1 | Forge, Fabric | 15.38+ | runs in game on both |

Each branch builds with its own `gradle.properties`; see BUILDING.md. Releases are tagged by Minecraft version, for example `v0.1.0+mc1.21.1`.
