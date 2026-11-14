# Explore and find a village

A job that walks a spiral around the bot until it sees what it was sent for, a biome or a structure, then goes home and reports what it found on the way. The mod bundles it as `jobs/explore.py`; start it with `/antcraft bot run jobs.explore village`. The words are what to look for, an id such as `minecraft:desert` or a part of one such as `village` or `ocean` (`all`, the default, walks the whole spiral); how many rings, 4 by default; and how far apart the waypoints are, 32 blocks. With the defaults the bot looks up to 128 blocks out, 81 waypoints in all.

```python
import lib.explore
from lib.home import go_home

what = ARGS[0] if len(ARGS) > 0 and ARGS[0] != "all" else None
rings = int(ARGS[1]) if len(ARGS) > 1 else 4
step = int(ARGS[2]) if len(ARGS) > 2 else 32

until = None if what is None else lambda s: len(lib.explore.seen(what)) > 0
r = lib.explore.explore(rings, step, until)
go_home()
lib.explore.report()
if not r["ok"]:
    warn("explore:", what, r["reason"])
```

## How it works

- [`explore`](../reference/modules/lib/explore.md) walks the waypoints of a square spiral around the bot, the centre first and then ring after ring, and at each one [`look`](../reference/modules/lib/explore.md)s: the [`biome`](../reference/world/biome.md) at its feet and half a step around, and the [`structures`](../reference/world/structures.md) within a step. Neither sense loads land, so the bot learns only of what it walks by, as a player would.
- What it sees goes into `memory["seen"]`, one position per biome per chunk and one per structure, so a second run starts knowing what the first found.
- `until` asks [`seen(what)`](../reference/modules/lib/explore.md) after each look; the walk stops at the first waypoint where it gives a position.
- [`go_home`](../reference/modules/lib/home.md) walks home, and [`report`](../reference/modules/lib/explore.md) prints one line per biome and structure seen with its position nearest home, which the bot's log shows (`/antcraft bot log`). Where `what` was never seen, a `warn` says `not_found`.

`ExploreGameTest` runs the bundled job in a test area as `jobs.explore nothing 1 2`: the bot walks the whole spiral of 2-block steps, as nothing it sees is called "nothing", goes home, reports the biome and warns `not_found`. Another test puts a plains village in the area's far corner and runs `lib.explore.explore` with the same small steps until it sees the village, from the third waypoint.

## See also

- [`lib/explore`](../reference/modules/lib/explore.md), [`biome`](../reference/world/biome.md), [`structures`](../reference/world/structures.md)
- [Library modules](../reference/index.md#library-modules) and the plan in [The script library](../design/library.md)
