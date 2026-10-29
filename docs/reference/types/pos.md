# `Pos`

A block position. Anything that takes a position also takes a tuple `(x, y, z)`.

- `x`, `y`, `z`: the coordinates.
- `+` and `-` with another `Pos` or a tuple.
- `up(n=1)`, `down`, `north`, `south`, `east`, `west`: the position `n` blocks that way.
- `distance(other)`: the distance to another position.
- Usable as a dict key.

## Making one

`Pos(x, y, z, dimension=None)` makes a position from three ints. With a tuple `(x, y, z)` or another `Pos` as the only argument, it converts it, so a tuple gets the fields and methods above.

| Parameter | Type | Default | Meaning |
|---|---|---|---|
| `x`, `y`, `z` | `int` | (required) | the coordinates; or `x` alone is a tuple of three ints or a `Pos` |
| `dimension` | `str` or `None` | `None` | the dimension id, such as `"minecraft:the_nether"`; `None` means the bot's dimension (a `Pos` given as `x` keeps its own) |

```python
chest = Pos(10, 64, -3)
print(chest.up().y)                # 65
corner = Pos((0, 64, 0))           # a tuple as a Pos
print(corner == Pos(0, 64, 0))     # True
print(isinstance(chest, Pos))      # True
```

A coordinate that is not an int (a float, a str, `None`) stops the script with an error, as does a `dimension` that is not a str or `None`. A tuple does not equal a `Pos` with `==`, even with the same coordinates: convert it first, `Pos(t) == p`.

## Dimension

`dimension` is the id of the dimension a position from `pos()`, `home()` or `portals()` is in, such as `"minecraft:overworld"`, and `None` for a position the script made, which means the dimension the bot is in (a tuple has none either).

Every function that takes a position refuses one whose `dimension` is not the bot's: an action fails at once with `wrong_dimension` (`goto`, `travel`, `mine`, `place`, `use`, `put`, `take`, and `craft`'s `table` and `smelt`'s `furnace`), `reachable` gives `False`, `block`, `container`, `mineshaft` and `mineshaft_at` give `None` and `ore_vein` gives `[]`. Only `set_home` takes one from any dimension, and `distance` and `+`, `-` work on the coordinates alone.

A `Pos` made from another with `+`, `-` or a step keeps its dimension (the left one's, if both have one). The dimension takes no part in `==` or printing: positions compare by their coordinates, so `pos() == home()` is true at the home's coordinates in any dimension; compare `dimension()` with `home_dimension()` too. It is saved with the script and in [`memory`](memory.md).
