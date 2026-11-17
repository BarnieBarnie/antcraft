# Farm kelp

The job `jobs/kelp_farm` farms kelp in a pool of water until a player stops it. Kelp smelts to dried kelp, a food, and nine dried kelp craft a dried kelp block, furnace fuel. Kelp regrows from its bottom block and is its own planting stock, so a pool planted once keeps giving.

```text
/antcraft bot run jobs.kelp_farm 10 62 10 14 62 12
/antcraft bot run jobs.kelp_farm 10 62 10 14 62 12 16
/antcraft bot run jobs.kelp_farm 10 62 10 14 62 12 32 dry
/antcraft bot run jobs.kelp_farm
```

The arguments are the corners `x1 y1 z1 x2 y2 z2` of the pool's floor, the blocks the kelp stands on (by default those of the last run), how far to look for kelp to plant, a furnace, a crafting table and fuel, in blocks (default 32), and `dry` to make dried kelp and dried kelp blocks of it ([Drying the kelp](#drying-the-kelp)). The bot says `planted 6 kelp` once it has planted the pool, and `cut 2 kelp` each round that cut some.

## The pool

The farm builds no water: it farms a pool that is already there, such as one dug into the ground and filled with a few water buckets. The pool is **two blocks deep or more**, water source blocks over a floor kelp stands on (any solid block but magma), with ground round it level with the top of the water, where the bot walks.

[`place`](../reference/blocks/place.md) and [`mine`](../reference/blocks/mine.md) reach 4 blocks from the bot's eyes, which gets to the floor of a pool two deep from its rim, not three. Where the rim is out of reach, they swim out and work from the surface of the water (#840), so a deeper pool is farmed too, a block more of it from the water for each block deeper. Under water a bot only swims up for air ([Bots](../features/bots.md)), so it is never sent down to work there: the floor of a pool deeper than about four blocks is out of reach from the surface as well, and left unplanted (its `place` fails `out_of_reach`).

| What | Where |
|---|---|
| kelp | on each floor block from `x1 z1` to `x2 z2`, in the water over it |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | at the rim, as high as the top of the water, from 2 north of the pool's north-west corner going east |

## The rounds

Each round the bot plants kelp on each floor block whose water lacks it, with kelp it carries or takes from the chests within the radius (the depot's too), mines the kelp two high or more from its top down to its second block, never the bottom block, which grows again (cut from the top, nothing breaks loose to float up), and stores what it carries in the depot. A round that cut nothing waits 100 ticks. Kelp grows on random ticks, so the pool must be within a few chunks of the bot for it to grow, as the bot stands at the pool.

## Drying the kelp

Without `dry` the bot stores raw kelp. With it (from a script, `jobs.kelp_farm.run(low, high, radius, True)`), each round after cutting it also:

1. takes the kelp the depot holds, up to a stack with what it carries, and smelts it to dried kelp in the nearest furnace within the radius, which it walks to. Where there is none it puts one down and takes it back after ([`lib.craft.smelt_here`](../reference/modules/lib/craft.md)), which needs a pickaxe and a furnace or what to craft one of. It burns a dried kelp block (20 kelp each), else coal or charcoal (8 each), carried or taken from the chests within the radius, else the best fuel it carries, as [`smelt`](../reference/crafting/smelt.md) picks it. Once the farm has made a block it fuels itself. A smelt takes 10 seconds a kelp.
2. takes the dried kelp the depot holds and crafts each 9 into a dried kelp block, at the nearest crafting table within the radius, else one it places from planks it carries ([`lib.craft.craft_here`](../reference/modules/lib/craft.md)). Fewer than 9 left over stay dried kelp, food.

The depot keeps raw kelp in its `kelp` chests and dried kelp and its blocks in its `dried` chests. Kelp in other chests, such as the one the bot planted from, is left alone as planting stock. Put a furnace and a crafting table by the depot, and a few coal in a chest nearby to start.

## Not yet

- Growing the farm: it builds no new water cells, as a cell two deep takes a few water buckets poured block by block.
