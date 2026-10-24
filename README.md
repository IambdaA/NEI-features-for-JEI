# NEI Features for JEI

An unofficial port of features from [GTNH NotEnoughItems](https://github.com/GTNewHorizons/NotEnoughItems) to
[Just Enough Items](https://github.com/mezz/JustEnoughItems), for **Minecraft 26.2 / NeoForge**. Not affiliated with the
NotEnoughItems authors.

## Features

- Bookmark panel with groups, todo-list and crafting-chain views, collapsed recipes, tag slots and multiple pages
- Favorite recipes and recipe trees (heart button in JEI's recipe screen)
- Crafting tree window with a stats panel (zoom, pan, collapse, inventory snapshot, search)
- Autocrafting (Shift+C), pulling items from chests (V), chat links (Ctrl+L)
- Highlight tips HUD and item info pages
- Crafting calculator

See [BUILDING.md](BUILDING.md) for the full list, keys and notes.

## Requirements

- Minecraft 26.2, NeoForge 26.2.0.90 or newer
- JEI 30.42 or newer

## Status

Early version. Parts have been tested in game, but not every feature in every setup. Bug reports are welcome in the
[issue tracker](../../issues).

Not ported from NEI: the crafting tree's screenshot button, NEI's search language, container items, drop chances and
fluids in the chain maths.

## Building

JDK 25, then `./gradlew build`. The jar is in `build/libs/`. `./gradlew runClient` starts a dev client with JEI.

## License

LGPL-3.0-or-later, see [LICENSE.txt](LICENSE.txt). Large parts are ports of GTNH NotEnoughItems (LGPL-3.0-or-later) by
Chicken-Bones, GTNewHorizons and the NotEnoughItems contributors, and keep that license and credit. The crafting tree
texture (`craftingtree.png`) is from NotEnoughItems too.
