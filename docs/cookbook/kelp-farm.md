# Farm kelp

The job `jobs/kelp_farm` farms kelp in a pool of water until a player stops it. Kelp smelts to dried kelp, a food, and nine dried kelp craft a dried kelp block, furnace fuel. Kelp regrows from its bottom block and is its own planting stock, so a pool planted once keeps giving.

```text
/antcraft bot run jobs.kelp_farm 10 62 10 14 62 12
/antcraft bot run jobs.kelp_farm 10 62 10 14 62 12 16
/antcraft bot run jobs.kelp_farm
```

The arguments are the corners `x1 y1 z1 x2 y2 z2` of the pool's floor, the blocks the kelp stands on (by default those of the last run), and how far to look for kelp to plant, in blocks (default 32). The bot says `planted 6 kelp` once it has planted the pool, and `cut 2 kelp` each round that cut some.

## The pool

The farm builds no water: it farms a pool that is already there, such as one dug into the ground and filled with a few water buckets. The pool is **two blocks deep**, water source blocks over a floor kelp stands on (any solid block but magma), with ground round it level with the top of the water, where the bot walks.

Two deep, as the bot works the pool from its rim: a bot never ends a walk swimming to place or mine a block, and [`place`](../reference/blocks/place.md) and [`mine`](../reference/blocks/mine.md) reach 4 blocks from its eyes, which gets to the floor of a pool two deep, not three. Under water a bot only swims up for air ([Bots](../features/bots.md)), so it is never sent down to work there. A spot of a deeper pool is left unplanted (its `place` fails `out_of_reach`).

| What | Where |
|---|---|
| kelp | on each floor block from `x1 z1` to `x2 z2`, in the water over it |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | at the rim, from 2 north of the pool's north-west corner going east |

## The rounds

Each round the bot plants kelp on each floor block whose water lacks it, with kelp it carries or takes from the chests within the radius (the depot's too), mines the kelp two high at its second block, never the bottom block, which grows again, and stores what it carries in the depot. A round that cut nothing waits 100 ticks. Kelp grows on random ticks, so the pool must be within a few chunks of the bot for it to grow, as the bot stands at the pool.

## Not yet

- Smelting the kelp to dried kelp, and crafting dried kelp blocks: the bot stores raw kelp.
- Growing the farm: it builds no new water cells, as a cell two deep takes a few water buckets poured block by block.
- Pools deeper than two blocks, which take a walk that may end swimming.
