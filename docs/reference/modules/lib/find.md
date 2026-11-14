# `lib/find`

Finders over [`scan`](../../world/scan.md): they take no ticks of their own and give a position or `None`. A module of the mod's default library: `import lib.find` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/find.py`, and a primitive: it imports nothing, and every skill may import it (see [Writing library functions](../../index.md#writing-library-functions)).

Each looks through the 256 blocks nearest the bot that `what` selects (`LIMIT`), so a search may pause as `scan` does. Where [`WHERE`](#where) is set and leaves out every one of those (or the finder's own filter does), it looks through 8 times as many, then 8 times that, until one passes or no more are within `radius`: a bot far outside the area still finds a block within it, at the cost of reading up to the whole radius.

## `nearest(what, radius=32, where=None)`

- **Returns** the [`Pos`](../../types/pos.md) of the nearest block within `radius` that the [selector](../../types/selectors.md) `what` selects and for which `where(p)` is true (any, where `where` is `None`); `None` if there is none.
- **Pauses** only as `scan` does.

```python
import lib.find

# The nearest chest east of the bot.
chest = lib.find.nearest("chest", 16, lambda p: p.x > pos().x)
```

## `exposed(what, radius=32, skip=None)`

- **Returns** the `Pos` of the nearest block within `radius` that `what` selects with air beside or above it (`is_open`), one at or above the bot's feet first, which it mines sideways without digging a pit; else the nearest exposed one below; `None` if there is none. Positions in the dict `skip` are left out.
- **Pauses** only as `scan` does.

```python
import lib.find

ore = lib.find.exposed("#c:ores/iron")
if ore is not None:
    for p in ore_vein(ore):
        mine(p)
```

## `in_colony(p)`

Whether the position `p` lies within the colony's radius of its core, measured across (x and z): the colony's edge is a column, so a block 40 below the core within the radius is in the colony (#576). `False` for a bot in no colony. [`colony/common`](../colony/common.md) gives it as its own. Done at once.

## `WHERE`

Where the library may look: a function of a [`Pos`](../../types/pos.md), or `None` for anywhere. `nearest`, `exposed`, [`lib.trees.next_tree`](trees.md) and [`lib.stock.chests`](stock.md#chestsradius32) leave out each position it is false for, so [`lib/get`](get.md) neither takes from a chest nor gathers a block outside it, and its cost estimates do not count on them. It starts as `in_colony` for a bot in a colony when the script starts, so every job a member runs keeps to the colony (#594), and `None` for any other bot; [`colony/common`](../colony/common.md) sets it to `in_colony` too. Where the `LIMIT` nearest blocks are all outside it, the finders look further (see above). [`lib.dig`](dig.md)'s `stairs_to` and `branch` dig nothing it is false for.

```python
import lib.find

home = pos()
lib.find.WHERE = lambda p: home.distance(p) <= 16
```

## `is_open(p)`

Whether a block beside `p` (north, south, east or west) or above it is air. Done at once.

## `is_air(p)`

Whether the block at `p` is loaded and air. Done at once.

## See also

[`scan`](../../world/scan.md), [`find_nearest`](../../world/find_nearest.md), [`lib/dig`](dig.md).
