# `lib/explore`

Exploring: walking a spiral around the bot and remembering the biomes and structures it sees on the way, so a script can ask where the nearest village or desert is. A module of the mod's default library: `import lib.explore`, then `lib.explore.explore(until=lambda s: lib.explore.seen("village"))` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/explore.py`, a skill over [`lib/retry`](retry.md) and [`lib/result`](result.md) (see [The script library](../../../design/library.md#layers)).

What the bot has seen is kept in [`memory`](../../types/memory.md)`["seen"]`, so it survives restarts and adds up over explores:

```text
{"biomes": {"minecraft:plains": [Pos(...), ...]}, "structures": {"minecraft:village_plains": [Pos(...)]}}
```

A biome keeps one position per 16 by 16 blocks (`CELL`), a structure one position, the centre of its box, so the dict stays small however far the bot goes. Like [`biome`](../../world/biome.md) and [`structures`](../../world/structures.md) it reads only loaded land: the bot has to walk there.

```python
import lib.explore

r = lib.explore.explore(until=lambda s: lib.explore.seen("village"))
if r["ok"]:
    say("a village at " + str(lib.explore.seen("village")[0]))
```

## `spiral(centre, step=32, rings=4)`

The waypoints of a square spiral around `centre`, `step` blocks apart and `rings` rings out: `centre` first, then east, and on clockwise (south, west, north), ring after ring. That is `(2 * rings + 1) ** 2` positions, all at `centre`'s `y`, the last one `rings * step` blocks east and north of `centre`. A finder, done at once.

## `look(radius=64, around=16)`

Records in `memory["seen"]` the biome at the bot's feet and at `around` blocks north, south, east and west of them (a biome once per cell), and each structure [`structures(radius)`](../../world/structures.md) lists (once, at the centre of its box; `radius` at most 128). Spots not loaded are left out. Gives `memory["seen"]`. Done at once.

## `explore(rings=4, step=32, until=None)`

Walks [`spiral`](#spiralcentre-step32-rings4)`(pos(), step, rings)` and [`look`](#lookradius64-around16)s at each waypoint: structures within `step`, biomes `step / 2` around, so the looks cover the land between the waypoints. A waypoint goes to the [`surface`](../../world/surface.md): where its column is not loaded yet, the bot first walks half way there, `step / 2` blocks short of it, which loads it (as [`goto`](../../movement/goto.md) loads the chunks ahead), and then goes to its surface; where it is still not loaded, as in the Nether, it keeps the bot's `y` and `goto` digs or pillars there; each walk is [`retry`](retry.md)'d 3 times, and a waypoint the bot cannot get to is looked at from where it stopped. Stops at the first waypoint where `until(memory["seen"])` is true.

- **Returns** a [`result()`](result.md) dict: `ok`, with `at` the bot's position where it stopped; with an `until` that never held, `reason` `not_found`.
- **Pauses** yes, for the walks: with the defaults, 81 waypoints over 256 by 256 blocks.

## `seen(what)`

The positions remembered of the biomes and structures `what` matches, nearest the bot first; `[]` if none. `what` matches an id itself, such as `"minecraft:desert"`, or is a part of its name, without a namespace: `"village"` matches every village and `"ocean"` every ocean biome, or is a biome tag with its `#`: `"#minecraft:is_forest"` matches every biome [`biome_tags`](../../world/biome_tags.md) puts in that tag (forest, birch forest, dark forest...), or a structure tag: `"#minecraft:village"` matches every village [`structure_tags`](../../world/structure_tags.md) puts in it. A finder, done at once.

## `report()`

Prints one line for each biome and structure remembered: its id and its position nearest the bot, such as `minecraft:village_plains at Pos(120, 70, -64)`. Done at once.

## See also

[`biome`](../../world/biome.md), [`biome_tags`](../../world/biome_tags.md), [`structures`](../../world/structures.md), [`structure_tags`](../../world/structure_tags.md), [Explore and find a village](../../../cookbook/explore.md), [`memory`](../../types/memory.md), [The script library](../../../design/library.md).
