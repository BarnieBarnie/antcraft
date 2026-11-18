# Grow a crop farm

The job `jobs/crop_farm` grows a farm of wheat, carrots, potatoes or beetroot from a cell's worth of seed and farms it until a player stops it. Where [`jobs.farm`](farm.md) tends one field you laid out, this one lays out its own fields, 9 by 9 cells, and builds another each time its harvest leaves it a cell's worth of seed: carrots and potatoes are their own seed, wheat and beetroot drop seeds. It runs on [`lib.cellfarm`](../reference/modules/lib/cellfarm.md), the engine of the [wood farm](wood-farm.md), so it lays out, stores, grows, resumes and moves as that does.

```text
/antcraft bot run jobs.crop_farm carrot
/antcraft bot run jobs.crop_farm wheat 10 64 10
/antcraft bot run jobs.crop_farm potato 10 64 10 4 24
```

The arguments are the crop, a crop, seed or harvest of [`lib.farm.CROPS`](../reference/modules/lib/farm.md) (`wheat`, `wheat_seeds`, `carrot`, `potato`, `beetroot`, ...); the farm's first corner `x y z` (by default where the bot stands, or where the last farm of that crop started); the most cells it grows to (by default as many as the bot can keep harvested while a cell ripens, from 600 ticks a harvest till it has learnt its own, see [`lib.cellfarm.most`](../reference/modules/lib/cellfarm.md#mostname-growth-guess), no more than the cells that get random ticks round its corner, see [`lib.cellfarm.limit`](../reference/modules/lib/cellfarm.md#limitf-name-size)); and how far to look for what it needs, in blocks (default 32). The farm is named after the crop block, `wheat_farm`, `carrots_farm`, `potatoes_farm` or `beetroots_farm`, and keeps its corner in `memory["crop_farms"]`.

| What | Where, from the corner |
|---|---|
| cell 1, 9 by 9 soil, its water source in the middle | `(0, -1, 0)` east and south, the water at `(4, -1, 4)` |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from `(0, 0, -2)` going east |

The steps:

```python
steps = [Step("cell", first_cell, (c, name, size, radius)),
         Step("farm", farm, (c, name, size, radius))]
```

## How it works

- **cell**: gets a cell's seed, 80 (`CELL`: all of 9 by 9 but the water), from the chests within the radius, else by [`lib.get`](../reference/modules/lib/get.md) (wheat seeds from grass), then builds cell 1 (`build_cell`): levels its floor and clears the block over it ([`lib.terrain.flatten`](../reference/modules/lib/terrain.md)), puts dirt where the floor is not soil, such as stone, pours a water source in the middle with a bucket (`jobs.farm.pour`: a water bucket from a chest, else an empty one filled at the nearest water, never at the farm's own), which wets the whole cell, gets a hoe and tills and plants it ([`lib.farm.tend`](../reference/modules/lib/farm.md)). With no water to pour it stops with `no_water: ...` and what it lacks, such as `no water bucket, nor a bucket or iron to make one` (`jobs.farm.lacks`).
- **farm**, round after round ([`lib.cellfarm.farm`](../reference/modules/lib/cellfarm.md#farmf-name-size-radius)):
    1. harvests each cell whose crops are all ripe, and replants it from the drops (`lib.farm.tend`), once the bot has 6 empty slots (`ROOM`);
    2. stores what it carries in the depot's `crops` chest (the harvest, extra seed, poisonous potatoes), the rest, such as the dirt a pour dug, in `other`; the bot keeps a cell's seed (80), its hoe and its buckets;
    3. tends each cell that lacks its water or a crop: pours the water again where it is gone and plants the bare spots, getting the seed it lacks; a cell with its water is left alone while the bot carries no seed and no chest within the radius holds any, so it does not walk there for nothing;
    4. builds the next cell where the farm has fewer than its most and the bot and the chests hold a cell's seed, 80, beyond what it carries; else says once what it lacks, such as `next cell: no_recipe, lacks {'minecraft:carrot': 40}`, and tries again 10 rounds later, or after the next harvest;
    5. where it harvested nothing, walks into its corner's chunk and waits 100 ticks.

A cell's water is kept: a bucket the farm fills, for a new cell or any pour, is never filled at one of its cells' sources, and a source that is gone is poured again in the next round. A dirt the farm gets is never dug from under its cells ([`lib.treefarm.get_outside`](../reference/modules/lib/treefarm.md)).

The farm grows by itself only as fast as its harvest gives seed beyond replanting: a cell of carrots or potatoes gives some 200 more than it takes back, wheat and beetroot fewer. Put seed in the depot's chests, or any chest within the radius, to grow it faster.

Run again, with no corner or the same one, it goes on farming; with another corner it moves there, carrying its depot's stock along (see the wood farm's [Move a farm](wood-farm.md#move-a-farm)).
