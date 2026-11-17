# Grow a sugarcane farm

The job `jobs/cane_farm` grows a sugarcane farm, for paper and books (an enchanting table, bookshelves), and farms it until a player stops it. Cane regrows from its bottom block and is its own planting stock, so the farm grows from its own harvest: each time the bot has or can get the cane one more cell takes, it builds it. It is a spec of [`lib/cellfarm`](../reference/modules/lib/cellfarm.md) (#829), the engine the [wood farm](wood-farm.md) runs on too, so its layout, depot, growth and rounds are the same; it is one [`lib/project`](../reference/modules/lib/project.md), so a stopped run goes on where it was.

```text
/antcraft bot run jobs.cane_farm
/antcraft bot run jobs.cane_farm 10 64 10
/antcraft bot run jobs.cane_farm 10 64 10 4 16
```

The arguments are the farm's corner `x y z` (by default where the bot stands, or where the last run started; another corner moves the farm there), the most cells it grows to (by default as many as keep growing while the bot stands at its corner, [`limit`](../reference/modules/lib/cellfarm.md#limitf-name-size)) and how far to look for what it needs, in blocks (default 32). The bot says `built cell 2` and `cells 2/12` as the farm grows, and `farm full` once it has all its cells.

## A cell

A cell is three floor blocks in a row going east, the floor being the block under the corner: water in the middle, dirt either side with cane on it, as cane needs water beside the block it stands on.

| What | Where, from the cell's low corner |
|---|---|
| dirt, cane on it | `(0, 0, 0)` and `(2, 0, 0)` |
| a water source | `(1, 0, 0)` |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from the corner `(0, 0, -2)` going east |

To build one, the bot clears the block over the floor, mines each side that is not soil (dirt, grass, mud, moss or sand stay; such as stone, with a pickaxe it gets) and puts dirt there, digs the middle and pours water into it with [`jobs.farm`'s `pour`](farm.md#the-bundled-job) (a water bucket from a chest, else an empty bucket filled at a water source within the radius, never one of the farm's own), and plants two cane from what it carries or the chests hold. The bot keeps its buckets; the cane, and the dirt and stone the build dug, go in the depot.

## The rounds

Each round, cane two or more high is cut from its second block up, never the bottom block, which grows again; the bot picks up the drops and stores them in the depot. A cell whose water is gone, which pops its cane off, gets water poured again from the bucket the bot kept (else one filled at a water source within the radius), and a side that lost its cane is planted again from the depot. Once the depot holds two cane, a cell's worth, the next round builds the next cell with them. Cane grows a block in some 22000 ticks, so a cell is ripe about once every 20 minutes; a round that cut nothing waits in the corner's chunk, where the cells get the random ticks they grow on.
