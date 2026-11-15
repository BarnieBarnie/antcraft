# `lib/cost`

The numbers the library's estimates use: how long the bot takes to walk, to break blocks and to smelt, what a death costs, and how many blocks a tool lasts. A module of the mod's default library: `import lib.cost` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/cost.py`, and a primitive: its functions are finders, they take no ticks, and it imports nothing (see [Writing library functions](../../index.md#writing-library-functions)).

The numbers are constants, rounded from the game's, not its break-time formula: good enough to choose between a stone and an iron tool, not to time a job to the tick.

## Constants

- `WALK`: ticks the bot takes to walk one block, `5`.
- `BREAK`: ticks to break one block, `BREAK[material][tier]`. `material` is `"stone"` (stone, cobblestone, deepslate: a pickaxe), `"ore"` (an ore: a pickaxe), `"wood"` (a log: an axe), `"dirt"` (dirt, sand, gravel: a shovel), `"table"` (a crafting table: an axe) or `"furnace"` (a furnace: a pickaxe), each as the game times a tool that drops the block; `tier` is `"wooden"`, `"stone"`, `"copper"`, `"iron"`, `"diamond"`, `"netherite"`, `"golden"` or `"hand"` (no tool). An ore broken with a pickaxe of too low a tier takes longer and drops nothing: check [`lib.tools.tier_for`](tools.md#tier_forwhat) first.
- `USES`: how many blocks a tool of each tier breaks before it is used up: wooden 59, stone 131, copper 190, iron 250, diamond 1561, golden 32, netherite 2031.
- `SMELT`: ticks a furnace takes to smelt one item, `200`.
- `RESPAWN`: ticks from a bot's death to its script running again at its home, `200`: the config `botRespawnTicks` by default (see [Death and respawn](../../../features/death.md)).
- `DESPAWN`: ticks what a bot drops lies before it is gone, `6000` (five minutes), as a player's drops.
- `AWAY_WALK`: ticks the walk back costs from a home in another dimension, `6000` (`DESPAWN`): a fixed guess, as the path search costs it.

## `walk(a, b)`

- **Returns** the ticks the bot takes to walk from [`Pos`](../../types/pos.md) `a` to `b`: `WALK` for each block along x, y and z. A path steps along x or z, never both at once, so a diagonal walk takes that long too. It does not look for a path; [`reachable`](../../movement/reachable.md) tells whether there is one.
- **Pauses** no.

## `breaking(n, material, tier)`

- **Returns** the ticks the bot takes to break `n` blocks of `material` with a tool of `tier` (`None` or `"hand"`: no tool): `n * BREAK[material][tier]`.
- **Pauses** no.

## `death(at, start=None)`

- **Returns** the ticks a death at [`Pos`](../../types/pos.md) `at` costs the bot: `RESPAWN`, then the walk from `start` (by default its [`home`](../../movement/home.md)) back to `at` for what it dropped. With no `start` and the home in another dimension (see [`home_dimension`](../../movement/home_dimension.md)), the walk back is `AWAY_WALK`, as the path search costs it (#699). A death costs nothing else but what it drops, and only when the bot is not back for it within `DESPAWN` ticks: a long fall, even a fatal one, can be cheaper than the climb down, and a walk's path weighs it so: a fatal fall costs all of it, and one that hurts the ticks the bot's food heals it in, each point at most its share of this ([Bots](../../../features/bots.md), #682, #708). `jobs/wood_farm` sets its home at the farm for that (see [Grow a wood farm](../../../cookbook/wood-farm.md)).
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
