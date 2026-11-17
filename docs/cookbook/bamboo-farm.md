# Grow a bamboo farm

The job `jobs/bamboo_farm` grows a bamboo farm, for scaffolding, sticks and furnace fuel, and farms it until a player stops it. Bamboo grows fastest of all plants, regrows from its bottom block and is its own planting stock, so the farm grows from its own harvest: each time the bot has or can get the bamboo one more cell takes, it builds it. It is a spec of [`lib/cellfarm`](../reference/modules/lib/cellfarm.md) (#831), the engine the [wood farm](wood-farm.md) and the [sugarcane farm](cane-farm.md) run on too, so its layout, depot, growth and rounds are the same; it is one [`lib/project`](../reference/modules/lib/project.md), so a stopped run goes on where it was.

```text
/antcraft bot run jobs.bamboo_farm
/antcraft bot run jobs.bamboo_farm 10 64 10
/antcraft bot run jobs.bamboo_farm 10 64 10 4 16
```

The arguments are the farm's corner `x y z` (by default where the bot stands, or where the last run started; another corner moves the farm there), the most cells it grows to (by default as many as keep growing while the bot stands at its corner, [`limit`](../reference/modules/lib/cellfarm.md#limitf-name-size)) and how far to look for what it needs, in blocks (default 32). The bot says `built cell 2` and `cells 2/12` as the farm grows, and `farm full` once it has all its cells.

## A cell

A cell is three floor blocks in a row going east, the floor being the block under the corner, each with bamboo on it. Bamboo needs no water.

| What | Where, from the cell's low corner |
|---|---|
| dirt, bamboo on it | `(0, 0, 0)`, `(1, 0, 0)` and `(2, 0, 0)` |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from the corner `(0, 0, -2)` going east |

To build one, the bot clears the blocks over the floor, mines each spot that is not soil (such as stone, with a pickaxe it gets) and puts dirt there, and plants three bamboo from what it carries or the chests hold. The bamboo, and the dirt and stone the build dug, go in the depot.

## The rounds

Each round, bamboo two or more high is broken at its second block, which drops the whole stalk above it and keeps the bottom block, which grows again. The drops scatter widely, so the bot sweeps them up with [`pick_up`](../reference/inventory/pick_up.md) within 12 blocks and stores them in the depot. A spot that lost its bamboo is planted again from the depot. Once the depot holds three bamboo, a cell's worth, the next round builds the next cell with them. A round that cut nothing waits in the corner's chunk, where the cells get the random ticks they grow on.
