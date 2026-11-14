# `lib/farm`

Farming: finding a field's soil and ripe crops, and tilling, planting and harvesting it. A module of the mod's default library: `import lib.farm`, then `lib.farm.tend(low, high)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/farm.py`, a skill over [`lib/result`](result.md) and [`lib/find`](find.md) written with [`block`](../../world/block.md), [`use`](../../blocks/use.md), [`place`](../../blocks/place.md) and [`mine`](../../blocks/mine.md) (see [The script library](../../../design/library.md#layers)). As a skill it assumes what it needs: a hoe to till with, the seeds to plant and an empty bucket to fill; [`lib/get`](get.md) gets those, as the bundled `jobs.farm` does ([Auto-farm](../../../cookbook/farm.md#the-bundled-job)). The colony farmer (`colony.farmer`) is built on it too.

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

The spots of the box from `low` to `high` (any two opposite corners) to farm, row by row: in each column, its top block that is neither air nor a crop, where that is farmland, dirt or grass with air or a crop of `CROPS` over it. It reads each column from the top of the box down to that block, so give the box of the soil, one block high, where the field is flat (two blocks a column), or a taller box over uneven ground. A finder, done at once.

## `till(spots)`

Tills each of `spots` that is dirt or grass with air over it, with a hoe the bot carries. **Returns** how many it tilled. **Pauses** yes, while it walks and tills.

## `plant(spots, seed=None)`

Plants `seed` on each of `spots` that is farmland with air over it, while the bot carries it; `None` plants the first seed of `CROPS` the bot carries. **Returns** how many it planted. **Pauses** yes.

## `harvest(spots)`

Harvests the ripe crop over each of `spots` and replants it with its own seed where the bot carries one, as it does after the harvest unless no seed dropped. Unripe crops are left. What it harvests goes into the bot's inventory. **Returns** how many it harvested. **Pauses** yes.

## `water(radius=32, keep=None)`

The nearest water source block within `radius` (through [`lib/find`](find.md), so `WHERE` applies), where an empty bucket fills; flowing water is left out, and so are the sources at `keep`, a list of positions, such as those a job poured in its field. `None` if there is none. A finder.

## `dry(spots)`

The spots of `spots` with no water within 4 blocks across, level with them or one up, where farmland dries out and turns back into dirt. It reads the box round the spots once, 4 blocks wider each way and two high. `[]` for a field that is all wet. A finder, done at once.

## `source(spots)`

The spot of `spots` where a water source would wet the most of `dry(spots)`, the one nearest the middle spot where several wet as many: the middle of a field that is all dry. `None` where none is dry. `jobs.farm` pours there and asks again until it gives `None`, one source per dry patch, so a field wider than 9 is watered all over. It is greedy, so a field may get a source more than the fewest that would wet it. A finder, done at once.

## `fill_bucket(radius=32, keep=None)`

Fills an empty bucket the bot carries at `water(radius, keep)`: [`use`](../../blocks/use.md) with the bucket, so the bot walks there first. As a skill it assumes the bucket; [`lib/get`](get.md) gets one, from a chest or made of three iron ingots, as `jobs.farm`'s `pour` does to water a field where `dry` gives spots, keeping off the sources it poured there ([Auto-farm](../../../cookbook/farm.md#the-bundled-job)), and `jobs.settle` through it ([Settle a base](../../../cookbook/settle.md)). `bucket_at_hand(radius)` of `jobs.farm` says whether an empty bucket is carried, in a chest, or made from a chest's iron for at most `BUCKET_TICKS` (1000), so watering never digs for ore. **Returns** a dict: `ok`, `reason` (`None`; `no_water` where no source is within `radius`; `no_item` with `missing` `{"minecraft:bucket": 1}` where the bot carries no empty bucket; or the reason `use` failed with, `at` the source). **Pauses** yes.

```python
import lib.farm
from lib.get import get

if lib.farm.water(16) is not None and get("bucket", 1, 16)["ok"]:
    lib.farm.fill_bucket(16)
```

## `tend(low, high, seed=None)`

`harvest`, `till` and `plant(spots, seed)` over the `field(low, high)`. **Returns** a dict: `ok`, `reason` (`None`; `blocked` where a spot of soil was left untilled, such as with no hoe, or farmland left bare though the bot still carries the seed, a passing failure that [`lib/retry`](retry.md) tries again; or `no_item` where farmland was left bare for want of seeds), `at` (the first spot left so for `blocked`, else `None`) and `missing` (`{seed: n}`, the bare spots, for `no_item`). **Pauses** yes.

`FarmGameTest` tests `field` on uneven ground, `harvest` on ripe and unripe crops, `tend` on a 3 by 3 field around water and failing `blocked` where it has no hoe to till with, and the job: once, twice as the crops ripen, giving borrowed carrots back, watering a dry field from a pool with an empty bucket from a chest, giving a borrowed water bucket back empty, and watering a dry row 11 long with a source per dry patch.
