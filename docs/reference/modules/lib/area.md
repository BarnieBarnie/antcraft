# `lib/area`

Clearing or filling a cuboid in one call, instead of loops over x, y and z. A module of the mod's default library: `import lib.area`, then `lib.area.clear(a, b)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/area.py`, written with [`mine`](../../blocks/mine.md), [`place`](../../blocks/place.md) and [`lib/retry`](retry.md).

The corners `a` and `b` are [`Pos`](../../types/pos.md) values or tuples, in any order; both are in the cuboid. Its positions are in the bot's dimension.

## `box(a, b)`

Every position of the cuboid once, as a list of `Pos`: bottom up, each layer in rows along x, one row after the other along z. `reversed(box(a, b))` is top down.

- **Pauses** no.

```python
import lib.area

for p in lib.area.box((0, 64, 0), (2, 64, 2)):
    print(p, block(p).id)
```

## `clear(a, b)`

Mines every block of the cuboid but air and fluids, top down, so nothing falls on the bot. The drops go into the bot's inventory as `mine` puts them there. Passing failures (`interrupted`, `timeout`, `blocked`) are tried again with `lib.retry.retry`.

- **Returns** a dict as [`dig_shaft`](shaft.md#dig_shaftlevels) gives: `ok`, `reason`, `at` and `missing` (always `{}`).
- **Pauses** yes, while it walks and digs.

| Reason | When |
|---|---|
| a reason of [`mine`](../../blocks/mine.md) | the block at `at` could not be mined, such as `no_tool` for stone without a pickaxe or `no_space` when the inventory is full; the blocks above it are gone |
| `not_loaded` | the position `at` is in a chunk that is not loaded |

## `fill(a, b, item)`

Places `item` (a [selector](../../types/selectors.md)) at every air position of the cuboid, bottom up, so each block rests on the one under it. Blocks already there stay. The bot should stand outside the cuboid: a block is not placed where it stands.

- **Returns** a dict as `clear` gives.
- **Pauses** yes, while it walks and places.

| Reason | When |
|---|---|
| `missing_ingredients` | the bot ran out of `item`: `at` is the first position left and `missing` is `{item: how many positions are left}` |
| a reason of [`place`](../../blocks/place.md) | the block at `at` could not be placed, such as `blocked` |

## Example

Clear a room 5 wide, 3 high and 5 deep beside the bot, then fill the holes in its floor with cobblestone:

```python
import lib.area

a = pos() + (1, 0, 1)
r = lib.area.clear(a, a + (4, 2, 4))
if r["ok"]:
    r = lib.area.fill(a.down(), a.down() + (4, 0, 4), "cobblestone")
if not r["ok"]:
    warn(f"stopped at {r['at']}: {r['reason']} {r['missing']}")
```

## See also

[`mine`](../../blocks/mine.md), [`place`](../../blocks/place.md), [`build`](../../building/build.md), [`lib/retry`](retry.md), [Modules & import](../../../language/modules.md#the-mods-library).
