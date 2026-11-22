# Grow a cactus farm

The job `jobs/cactus_farm` grows a cactus farm, for green dye (cactus smelted) and furnace fuel, and farms it until a player stops it. Cactus regrows from its bottom block and is its own planting stock, so the farm grows from its own harvest: each time the bot has or can get the cactus one more cell takes, it builds it. It is a spec of [`lib/cellfarm`](../reference/modules/lib/cellfarm.md) (#830), the engine the [wood farm](wood-farm.md) and the [sugarcane farm](cane-farm.md) run on too, so its layout, depot, growth and rounds are the same; it is one [`lib/project`](../reference/modules/lib/project.md), so a stopped run goes on where it was.

```text
/antcraft bot run jobs.cactus_farm
/antcraft bot run jobs.cactus_farm 10 64 10
/antcraft bot run jobs.cactus_farm 10 64 10 4 16
```

The arguments are the farm's corner `x y z` (by default where the bot stands, or where the last run started; another corner moves the farm there), the most cells it grows to (by default as many as keep growing while the bot stands at its corner, [`limit`](../reference/modules/lib/cellfarm.md#limitf-name-size)) and how far to look for what it needs, in blocks (default 32). The bot says `built cell 2` and `cells 2/12` as the farm grows, and `farm full` once it has all its cells.

After the other arguments, `storage x y z` gives the colony store: the job takes its tools and materials from the chests within 8 blocks of `x y z` first, however far and wherever they are ([`lib.stock.STORAGE`](../reference/modules/lib/stock.md#storage)). A colony member without it uses the store its colony published ([`lib.depot.publish_store`](../reference/modules/lib/depot.md)).

## A cell

A cactus breaks when any block stands beside it, so a cell is 2 by 2 floor blocks, the floor being the block under the corner, with cactus on sand at two opposite corners: a checkerboard, with nothing beside either cactus.

| What | Where, from the cell's low corner |
|---|---|
| sand, cactus on it | `(0, 0, 0)` and `(1, 0, 1)` |
| the floor left as it is | `(1, 0, 0)` and `(0, 0, 1)` |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from the corner `(0, 0, -2)` going east |

To build one, the bot clears the blocks over the floor, mines each corner that is not sand (such as stone, with a pickaxe it gets) and puts sand there, got from a chest or dug within the radius, never from under the farm's cells, and plants two cactus from what it carries or the chests hold. The cactus, and the cobblestone the build dug, go in the depot.

## The rounds

Each round, a cactus two or more high is cut from the top down to its second block, never the bottom block, which grows again. Top down, as a cactus block whose support is broken drops its item onto the cactus below, which destroys it; the bot's own mining puts the drops straight in its inventory, and it picks up any that fell beside. It stores them in the depot. A corner that lost its cactus is planted again from the depot. Once the depot holds two cactus, a cell's worth, the next round builds the next cell with them. Cactus grows a block in some 22000 ticks, so a cell is ripe about once every 20 minutes; a round that cut nothing waits in the corner's chunk, where the cells get the random ticks they grow on, on a block touching no cell, not even diagonally, where the chunk has one (#887).

A cactus hurts whoever brushes it, so a bot's paths keep off the blocks beside one wherever a few blocks round avoid them ([Bots](../features/bots.md)), and it mines a cactus from a block off where it can.
