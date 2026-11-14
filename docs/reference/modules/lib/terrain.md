# `lib/terrain`

Terrain work for building projects: levelling a plot before building on it. A module of the mod's default library: `import lib.terrain`, then `lib.terrain.flatten(low, high, y)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/terrain.py`, a skill over [`lib/area`](area.md) and [`lib/result`](result.md) (see [The script library](../../../design/library.md#layers)). As a skill it assumes what it needs: the tools to dig with and the blocks to fill with; [`lib/get`](get.md) gets those, as the bundled `jobs.flatten` does ([below](#the-bundled-job)).

```python
import lib.terrain
from lib.get import ensure_tool, get

low, high = Pos(10, 62, 10), Pos(17, 70, 17)
n = lib.terrain.needs(low, high, 64)
for kind, uses in n["tools"].items():
    ensure_tool(kind, uses)
get("dirt", n["fill"])
r = lib.terrain.flatten(low, high, 64)
```

## `needs(low, high, y)`

What `flatten(low, high, y)` takes, as a dict: `fill`, the blocks it places; `dig`, the blocks it mines; and `tools`, `{kind: blocks}` of the blocks to mine by the tool whose `#minecraft:mineable/<kind>` tag they carry, for the kinds of `KINDS` (`"shovel"`, `"pickaxe"`, `"axe"`), such as `{'pickaxe': 1, 'shovel': 2}`. The blocks it digs drop items that can be fill too, so a plot with as much bump as hole needs no more fill than it digs. A finder, done at once.

## `flatten(low, high, y, fill="minecraft:dirt")`

Levels the plot of the box from `low` to `high` (any two opposite corners) at `y`: mines every block of the box above `y` but air and fluids, top down ([`lib.area.clear`](area.md)), then, in each column that is air, water or lava at `y`, places `fill` (an item, or a list of items of which each block takes the first the bot has) from the lowest such block under it, down to solid ground or the box's low y, up to `y`. It fills layer by layer, bottom up, and in each layer the water and lava source blocks first, so the flowing water they fed drains away; it reads each block again just before it places, as flowing water comes and goes. A waterlogged block is no hole. The box's high y is the top it digs to and its low y the deepest it fills, so give it the heights of the plot's highest and lowest ground. The drops go into the bot's inventory, so dirt it digs fills the holes. **Returns** a dict: `ok`, `reason` (`None`, the reason of the [`mine`](../../blocks/mine.md) or [`place`](../../blocks/place.md) that failed, or `missing_ingredients` once the bot runs out of `fill`), `at` (where it failed) and `missing` (`{fill: blocks left to place}`, the first item of a list). Run it again once the bot carries more `fill`: what is level is left. **Pauses** yes, while it walks, mines and places.

## `holes(low, high, y)` and `digs(low, high, y)`

The finders `needs` and `flatten` are built on: `holes` one `(bottom, top)` per column to fill (air, water or lava), `top` at `y`; `digs` the positions to mine.

## The bundled job

`/antcraft bot run jobs.flatten <x1> <z1> <x2> <z2> [y] [fill]`, such as `jobs.flatten 10 10 17 17 64` or `jobs.flatten 10 10 17 17 cobblestone`, levels the plot between the two corners at `y`, by default its middle (median) ground height, read with [`surface`](../../world/surface.md), and fills its holes with `fill`. Without one it fills with what the bot carries or digs of dirt, cobblestone and stone (`FILLS`), and gets dirt once it runs out. It digs up to the plot's highest ground, but no more than 16 blocks over `y`, and fills down to its lowest. It gets a tool of each kind `needs` lists with [`ensure_tool`](get.md) (a shovel or axe only where it beats the hand), flattens, gets the fill the dug blocks and its inventory fell short of with [`get`](get.md) and flattens again, and gives back what it borrowed. It warns `flatten: <reason>` on a failure.

`TerrainGameTest` tests `needs` and `flatten` on a 4 by 4 plot with a bump, a stone and a hole, `flatten` on a hole of water and one of lava, and the job on it: at its median height, with a fill it is given, with the cobblestone it carries when given none, and getting the dirt it lacks from a chest before it flattens again.
