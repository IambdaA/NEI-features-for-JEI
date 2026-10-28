# NEI Features for JEI

An unofficial port of features from [GTNH NotEnoughItems](https://github.com/GTNewHorizons/NotEnoughItems) to
[Just Enough Items](https://github.com/mezz/JustEnoughItems), for **Minecraft 1.21.1 on NeoForge and on Fabric** (this branch, one project with two jars; other versions have their own builds). Not affiliated with the
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

- Minecraft 1.21.1 with NeoForge 21.1, or Fabric (loader 0.16+ and Fabric API)
- JEI 19.x

## Status

Early version. Parts have been tested in game, but not every feature in every setup. Bug reports are welcome in the
[issue tracker](../../issues).

Not ported from NEI: collapsible item groups and NC/chance badges (JEI has no API for either), drop chances and fluids in the
chain maths (JEI gives no chance data on recipe slots, and the addon only knows items).

## Building

A JDK that can start Gradle (Gradle itself then runs on JDK 25, which it downloads if missing; the mod is compiled for Java 21), then `./gradlew build`. The jars are `neoforge/build/libs/neiaddon-neoforge-1.21.1-*.jar` and `fabric/build/libs/neiaddon-fabric-1.21.1-*.jar`.
`./gradlew :neoforge:runClient` or `./gradlew :fabric:runClient` starts a dev client with JEI. The shared code is in `common/`.

## License

LGPL-3.0-or-later, see [LICENSE.txt](LICENSE.txt). Large parts are ports of GTNH NotEnoughItems (LGPL-3.0-or-later) by
Chicken-Bones, GTNewHorizons and the NotEnoughItems contributors, and keep that license and credit. The crafting tree
texture (`craftingtree.png`) is from NotEnoughItems too.

## Versions

| Branch | Minecraft | Loader | JEI | Status |
| --- | --- | --- | --- | --- |
| `1.21.1` (this branch) | 1.21.1 | NeoForge, Fabric | 19.x | runs in game on both |
| `26.2` | 26.2 | NeoForge | 30.x | works in game |
| `26.1.x` | 26.1, 26.1.1, 26.1.2 | NeoForge | 29.x | one jar, not tested in game yet |
| `1.20.1` | 1.20.1 | Forge | 15.38+ | runs in game |

Each branch builds with its own `gradle.properties`; see BUILDING.md. Releases are tagged by Minecraft version, for example `v0.1.0+mc1.21.1`.
