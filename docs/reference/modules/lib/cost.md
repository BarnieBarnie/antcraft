# `lib/cost`

The numbers the library's estimates use: how long the bot takes to walk, to break blocks and to smelt, and how many blocks a tool lasts. A module of the mod's default library: `import lib.cost` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/cost.py`, and a primitive: its functions are finders, they take no ticks, and it imports nothing (see [Writing library functions](../../index.md#writing-library-functions)).

The numbers are constants, rounded from the game's, not its break-time formula: good enough to choose between a stone and an iron tool, not to time a job to the tick.

## Constants

- `WALK`: ticks the bot takes to walk one block, `5`.
- `BREAK`: ticks to break one block, `BREAK[material][tier]`. `material` is `"stone"` (stone, cobblestone, deepslate: a pickaxe), `"ore"` (an ore: a pickaxe), `"wood"` (a log: an axe) or `"dirt"` (dirt, sand, gravel: a shovel); `tier` is `"wooden"`, `"stone"`, `"copper"`, `"iron"`, `"diamond"`, `"netherite"`, `"golden"` or `"hand"` (no tool). An ore broken with a pickaxe of too low a tier takes longer and drops nothing: check [`lib.tools.tier_for`](tools.md#tier_forwhat) first.
- `USES`: how many blocks a tool of each tier breaks before it is used up: wooden 59, stone 131, copper 190, iron 250, diamond 1561, golden 32, netherite 2031.
- `SMELT`: ticks a furnace takes to smelt one item, `200`.

## `walk(a, b)`

- **Returns** the ticks the bot takes to walk from [`Pos`](../../types/pos.md) `a` to `b`: `WALK` for each block along x, y and z. It does not look for a path; [`reachable`](../../movement/reachable.md) tells whether there is one.
- **Pauses** no.

## `breaking(n, material, tier)`

- **Returns** the ticks the bot takes to break `n` blocks of `material` with a tool of `tier` (`None` or `"hand"`: no tool): `n * BREAK[material][tier]`.
- **Pauses** no.

## Example

Whether to make an iron axe for a forest of 200 logs, or wear out stone axes:

```python
import lib.cost

stone = lib.cost.breaking(200, "wood", "stone")
iron = lib.cost.breaking(200, "wood", "iron")
axes = (200 + lib.cost.USES["stone"] - 1) // lib.cost.USES["stone"]
print(f"stone: {stone} ticks and {axes} axes, iron: {iron} ticks and 1 axe")
```

## See also

[`lib/tools`](tools.md), [Writing library functions](../../index.md#writing-library-functions).
