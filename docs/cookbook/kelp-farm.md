# Farm kelp

The job `jobs/kelp_farm` farms kelp in a pool of water until a player stops it. Kelp smelts to dried kelp, a food, and nine dried kelp craft a dried kelp block, furnace fuel. Kelp regrows from its bottom block and is its own planting stock, so a pool planted once keeps giving.

```text
/antcraft bot run jobs.kelp_farm 10 62 10 14 62 12
/antcraft bot run jobs.kelp_farm 10 62 10 14 62 12 16
/antcraft bot run jobs.kelp_farm 10 62 10 14 62 12 32 dry
/antcraft bot run jobs.kelp_farm 10 62 10 14 62 12 32 dry 18
/antcraft bot run jobs.kelp_farm
```

The arguments are the corners `x1 y1 z1 x2 y2 z2` of the pool's floor, the blocks the kelp stands on (by default those of the last run), how far to look for kelp to plant, a furnace, a crafting table and fuel, in blocks (default 32), `dry` to make dried kelp and dried kelp blocks of it ([Drying the kelp](#drying-the-kelp)), and after it how many dried kelp to keep as food, not pressed into blocks (default 0). The bot says `planted 6 kelp` once it has planted the pool, and `cut 2 kelp` each round that cut some.

## The pool

Without `cells` ([Growing water cells](#growing-water-cells)) the farm builds no water: it farms a pool that is already there, such as one dug into the ground and filled with a few water buckets. The pool is **two blocks deep or more**, water source blocks over a floor kelp stands on (any solid block but magma), with ground round it level with the top of the water, where the bot walks.

[`place`](../reference/blocks/place.md) and [`mine`](../reference/blocks/mine.md) reach 4 blocks from the bot's eyes, which gets to the floor of a pool two deep from its rim, not three. Where the rim is out of reach, they swim out and work from the surface of the water (#840), so a deeper pool is farmed too, a block more of it from the water for each block deeper. Under water a bot only swims up for air ([Bots](../features/bots.md)), so it is never sent down to work there: the floor of a pool deeper than about four blocks is out of reach from the surface as well, and left unplanted (its `place` fails `out_of_reach`).

| What | Where |
|---|---|
| kelp | on each floor block from `x1 z1` to `x2 z2`, in the water over it |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | at the rim, as high as the top of the water, from 2 north of the pool's north-west corner going east |

## The rounds

Each round the bot plants kelp on each floor block whose water lacks it, with kelp it carries or takes from the chests within the radius (the depot's too), mines the kelp two high or more from its top down to its second block, never the bottom block, which grows again (cut from the top, nothing breaks loose to float up), and stores what it carries in the depot. A round that cut nothing waits 100 ticks. Kelp grows on random ticks, so the pool must be within a few chunks of the bot for it to grow, as the bot stands at the pool.

## Drying the kelp

Without `dry` the bot stores raw kelp. With it (from a script, `jobs.kelp_farm.run(low, high, radius, True, food)`), each round after cutting it also:

1. takes the kelp the depot holds, up to a stack with what it carries, and smelts it to dried kelp in the nearest furnace within the radius, which it walks to. Where there is none it puts one down and takes it back after ([`lib.craft.smelt_here`](../reference/modules/lib/craft.md)), which needs a pickaxe and a furnace or what to craft one of. It burns a dried kelp block (20 kelp each), else coal or charcoal (8 each), carried or taken from the chests within the radius, else the best fuel it carries, as [`smelt`](../reference/crafting/smelt.md) picks it. Once the farm has made a block it fuels itself. A smelt takes 10 seconds a kelp. Fuel it took and did not burn, such as a coal while the furnace still burns the last one, goes back to the chest it came from, as does kelp over a stack.
2. takes the dried kelp the depot holds and crafts each 9 into a dried kelp block, at the nearest crafting table within the radius, else one it places from planks it carries ([`lib.craft.craft_here`](../reference/modules/lib/craft.md)), but the food count: with `dry 18`, 18 dried kelp stay dried kelp, food, and only those over it are pressed. Fewer than 9 left over stay dried kelp too.

The depot keeps raw kelp in its `kelp` chests and dried kelp and its blocks in its `dried` chests. Kelp in other chests, such as the one the bot planted from, is left alone as planting stock. Put a furnace and a crafting table by the depot, and a few coal in a chest nearby to start.

## Growing water cells

With `cells` first the job builds its own water instead of farming a pool: a farm of water cells that grows a cell at a time on [`lib.cellfarm`](../reference/modules/lib/cellfarm.md), as the [sugarcane farm](cane-farm.md) does (#857).

```text
/antcraft bot run jobs.kelp_farm cells
/antcraft bot run jobs.kelp_farm cells 10 63 10
/antcraft bot run jobs.kelp_farm cells 10 63 10 8 16
/antcraft bot run jobs.kelp_farm cells 10 63 10 8 16 dry 18
/antcraft bot run jobs.kelp_farm cells 10 63 10 8 16 no 0 3
```

The arguments after `cells` are the farm's corner `x y z` (by default where the bot stands, or where the last run started), the most cells it grows to (by default as many as keep growing round its corner), the radius (default 32), `dry` (any other word for no drying) and the food count as for a pool, and how deep a cell is (default 2). From a script, `jobs.kelp_farm.grow(at, size, radius, dried, food, depth)`.

A cell is one column of the ground under the corner's level, dug two deep, or `depth` deep: water in each block, kelp on the block under them, which must be solid. The cells go east and south of the corner with a block of path between them, the depot north of them, as for every `lib.cellfarm` farm. To build one, the bot clears the block over the cell, digs its top block and pours water into it, then digs each block under that water in turn, so the water falls into it, and pours a source there over the falling water, and plants a kelp in the bottom one. In that order because a bucket's look goes through water: poured over a kelp, the water would go into the kelp and be lost. Kelp grows only into a water source, so every block over the kelp holds one. It pours with [`jobs.farm`'s `pour`](farm.md#the-bundled-job): a water bucket from a chest, else an empty bucket filled at a water source within the radius, never one of the farm's own. A cell takes a water bucket a block deep and a kelp; the bot keeps its buckets, and the stone it dug goes in the depot.

Each round, kelp grown up to a cell's top block is cut from the surface, from the top down to the block over its bottom one, which is left to grow again, and stored in the depot's `kelp` chests: a cell `depth` deep gives `depth - 1` kelp a cut; a cell whose kelp is gone is planted again from the depot. A cell whose blocks over the kelp lost their water is poured again, from the lowest up, the bucket aimed at a side of the hole ([`use`](../reference/blocks/use.md)'s `face`) so its water goes into the hole and not into the kelp (#877). Once the depot holds a kelp the next round builds the next cell with it. Kelp grows a block in some 9800 ticks, so a cell gives a kelp about every 8 minutes, however deep. A deeper cell is cut less often, so the bot walks less for each kelp. The bot works a cell from where [`place`](../reference/blocks/place.md) and [`mine`](../reference/blocks/mine.md) reach, as in a [deep pool](#the-pool), so a cell deeper than about four blocks is out of reach and its kelp never planted.

With `dry`, each round starts by drying the depot's kelp as for a pool ([Drying the kelp](#drying-the-kelp)), the dried kelp and its blocks going in the depot's `dried` chests, but one kelp stays raw in the depot to plant the next cell or replant one (#877).

## Not yet

- The depth is not kept with the farm: run it again with another `depth` and its cells built already are farmed as that deep.
