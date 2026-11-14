# `lib/farm`

Farming: finding a field's soil and ripe crops, and tilling, planting and harvesting it. A module of the mod's default library: `import lib.farm`, then `lib.farm.tend(low, high)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/farm.py`, a skill over [`lib/result`](result.md) written with [`block`](../../world/block.md), [`use`](../../blocks/use.md), [`place`](../../blocks/place.md) and [`mine`](../../blocks/mine.md) (see [The script library](../../../design/library.md#layers)). As a skill it assumes what it needs: a hoe to till with and the seeds to plant; [`lib/get`](get.md) gets those, as the bundled `jobs.farm` does ([Auto-farm](../../../cookbook/farm.md#the-bundled-job)). The colony farmer (`colony.farmer`) is built on it too.

```python
import lib.farm
from lib.get import ensure_tool

ensure_tool("hoe")
r = lib.farm.tend(Pos(10, 63, 10), Pos(14, 63, 14), "carrot")
if not r["ok"]:
    say("short of " + str(r["missing"]))
```

## `CROPS`

The crops it grows, each `(crop block, seed item, harvest item, ripe age)`: wheat (ripe at age 7), carrots, potatoes (both 7) and beetroot (3), wheat first, so it is planted where the bot carries several kinds of seed. `POISON` is the poisonous potato, `SOIL` the blocks a hoe tills (dirt and grass).

## `crop(name)`

The entry of `CROPS` whose crop, seed or harvest is `name`, with or without `minecraft:`, such as `"carrot"` or `"wheat_seeds"`; `None` for no crop. Done at once.

## `ripe(pos)`

The crop block id at `pos`, such as `"minecraft:wheat"`, where a crop of `CROPS` grows there ripe, else `None`. A finder, done at once.

## `field(low, high)`

The spots of the box from `low` to `high` (any two opposite corners) to farm: farmland, dirt or grass with air or a crop of `CROPS` over it, row by row. Give the box of the soil, one block high; it reads two blocks a column of the box. A finder, done at once.

## `till(spots)`

Tills each of `spots` that is dirt or grass with air over it, with a hoe the bot carries. **Returns** how many it tilled. **Pauses** yes, while it walks and tills.

## `plant(spots, seed=None)`

Plants `seed` on each of `spots` that is farmland with air over it, while the bot carries it; `None` plants the first seed of `CROPS` the bot carries. **Returns** how many it planted. **Pauses** yes.

## `harvest(spots)`

Harvests the ripe crop over each of `spots` and replants it with its own seed where the bot carries one, as it does after the harvest unless no seed dropped. Unripe crops are left. What it harvests goes into the bot's inventory. **Returns** how many it harvested. **Pauses** yes.

## `tend(low, high, seed=None)`

`harvest`, `till` and `plant(spots, seed)` over the `field(low, high)`. **Returns** a dict: `ok`, `reason` (`None`, or `no_item` where farmland was left bare for want of seeds), `at` (`None`) and `missing` (`{seed: n}`, the bare spots). **Pauses** yes.

`FarmGameTest` tests `harvest` on ripe and unripe crops, `tend` on a 3 by 3 field around water, and the job.
