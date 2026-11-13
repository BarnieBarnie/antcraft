# `lib/find`

Finders over [`scan`](../../world/scan.md): they take no ticks of their own and give a position or `None`. A module of the mod's default library: `import lib.find` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/find.py`, and a primitive: it imports nothing, and every skill may import it (see [Writing library functions](../../index.md#writing-library-functions)).

Each looks through the 256 blocks nearest the bot that `what` selects (`LIMIT`), so a search may pause as `scan` does.

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

## `WHERE`

Where the library may look: a function of a [`Pos`](../../types/pos.md), or `None` (the default) for anywhere. `nearest`, `exposed`, [`lib.trees.next_tree`](trees.md) and [`lib.stock.chests`](stock.md#chestsradius32) leave out each position it is false for, so [`lib/get`](get.md) neither takes from a chest nor gathers a block outside it, and its cost estimates do not count on them. [`colony/common`](../colony/common.md) sets it to `in_colony`. Each finder looks through the `LIMIT` nearest blocks before it, so a bot far outside the area may find none of the blocks within.

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
