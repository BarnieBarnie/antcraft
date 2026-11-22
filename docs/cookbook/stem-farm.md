# Grow a pumpkin or melon farm

The job `jobs/stem_farm` grows a pumpkin or melon farm from a few seeds and farms it until a player stops it (the stop command or the bot's screen). A stem keeps fruiting once it is grown, so the farm replants only a stem that is gone: each round it mines the fruit beside its stems and stores it in a depot, and each time it has or can get the 4 seeds a cell takes, it builds the next cell. It is a spec of [`lib/cellfarm`](../reference/modules/lib/cellfarm.md), the engine the [wood farm](wood-farm.md) runs on too, so it lays out, grows, stores, moves and resumes as that one does.

```text
/antcraft bot run jobs.stem_farm pumpkin
/antcraft bot run jobs.stem_farm melon 10 64 10
/antcraft bot run jobs.stem_farm pumpkin 10 64 10 6 16
```

The arguments are the kind, `pumpkin` or `melon`; the farm's first corner `x y z` (by default where the bot stands, or where the last farm of that kind started; another corner moves the farm there); the most cells it grows to (by default 24 till the farm has learnt how long its harvests take, then as many as the bot keeps up with, see [`lib.cellfarm.most`](../reference/modules/lib/cellfarm.md#mostname-growth-guess), but no more than the cells that get random ticks while the bot is at the corner, see [`lib.cellfarm.limit`](../reference/modules/lib/cellfarm.md#limitf-name-size)) and how far to look for what it needs, in blocks (default 32). The bot says `built cell 2` and `cells 2/9` as the farm grows, and `farm full` once it has all its cells.

After the other arguments, `storage x y z` gives the colony store: the job takes its tools and materials from the chests within 8 blocks of `x y z` first, however far and wherever they are ([`lib.stock.STORAGE`](../reference/modules/lib/stock.md#storage)). A colony member without it uses the store its colony published ([`lib.depot.publish_store`](../reference/modules/lib/depot.md)).

## The cell

A cell is 3 by 3 blocks, its floor the block under the corner's level:

```text
S D S     S  a stem on farmland
D W D     D  a dirt fruit spot
S D S     W  water, which keeps the farmland wet
```

A grown stem puts its fruit on a free block beside it that stands on dirt, grass or farmland: one of the fruit spots of its cell, or the path round it. The farm mines every pumpkin or melon beside a stem, wherever it grew, with the best tool the bot carries (an axe where it has one), never the stem or the soil under the fruit, so the spot stays free and solid for the next. A stem a mob trampled or a player broke is planted again: the bot tills its dirt with a hoe and plants a seed from the depot (#844).

| What | Where, from the corner |
|---|---|
| cell 1 | `(0, -1, 0)` east and south |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from `(0, 0, -2)` going east |

## Building a cell

To build a cell the bot carries 4 seeds of the kind. It takes them from the chests within the radius, else crafts them ([`lib/get`](../reference/modules/lib/get.md)): 4 pumpkin seeds from one stored pumpkin, a melon seed from each melon slice. So the first cell takes 4 seeds, from a chest or the bot's inventory, and every cell after it grows from the farm's own harvest.

It then clears the blocks over the floor and fills the floor's holes with dirt ([`lib.terrain.flatten`](../reference/modules/lib/terrain.md)), puts dirt where a stem or fruit spot is not soil, such as stone, pours a water source in the middle (a water bucket from a chest, else a bucket filled at the nearest water that is not another cell's, as [`jobs.farm`](farm.md) pours), tills the stems' dirt with a hoe it gets ([`ensure_tool`](../reference/modules/lib/get.md)) and plants the seeds.

The depot keeps the fruit and seeds in one category, `harvest`; the dirt mined for the water and anything else the bot picks up go in chests of their own. The bot keeps its empty buckets for the next cell's water.

## Limits

- Light: a stem grows only with light 9 or more over it; the farm places no torches, so a farm built in the dark or underground grows only by day.
