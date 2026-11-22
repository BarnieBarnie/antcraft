# Grow a sweet berry farm

The job `jobs/berry_farm` grows a sweet berry farm, food that needs no replanting and no water, and farms it until a player stops it. A ripe bush is picked, not broken: it stays and grows berries again. Each time the bot has or can get the berries one more cell takes, it builds it, from its own harvest. It is a spec of [`lib/cellfarm`](../reference/modules/lib/cellfarm.md) (#832), the engine the [wood farm](wood-farm.md) and the [bamboo farm](bamboo-farm.md) run on too, so its layout, depot, growth and rounds are the same; it is one [`lib/project`](../reference/modules/lib/project.md), so a stopped run goes on where it was.

```text
/antcraft bot run jobs.berry_farm
/antcraft bot run jobs.berry_farm 10 64 10
/antcraft bot run jobs.berry_farm 10 64 10 4 16
```

The arguments are the farm's corner `x y z` (by default where the bot stands, or where the last run started; another corner moves the farm there), the most cells it grows to (by default as many as keep growing while the bot stands at its corner, [`limit`](../reference/modules/lib/cellfarm.md#limitf-name-size)) and how far to look for what it needs, in blocks (default 32). The bot says `built cell 2` and `cells 2/12` as the farm grows, and `farm full` once it has all its cells.

After the other arguments, `storage x y z` gives the colony store: the job takes its tools and materials from the chests within 8 blocks of `x y z` first, however far and wherever they are ([`lib.stock.STORAGE`](../reference/modules/lib/stock.md#storage)). A colony member without it uses the store its colony published ([`lib.depot.publish_store`](../reference/modules/lib/depot.md)).

## A cell

A cell is three floor blocks in a row going east, the floor being the block under the corner, each dirt with a sweet berry bush on it.

| What | Where, from the cell's low corner |
|---|---|
| dirt, a bush on it | `(0, 0, 0)`, `(1, 0, 0)` and `(2, 0, 0)` |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from the corner `(0, 0, -2)` going east |

To build one, the bot clears the blocks over the floor, puts dirt where the floor is no soil (mining what is there, such as stone, with a pickaxe it gets) and plants three bushes. A cell takes 3 sweet berries, from what the bot carries or the chests hold. What the build dug goes in the depot.

## The rounds

Each round, the bot picks each bush of age 2 or 3, ripe, with [`use`](../reference/blocks/use.md), as a player's right click does: the bush drops its berries (1 or 2 at age 2, 2 or 3 at age 3) and stays, back at age 1. It [picks up](../reference/inventory/pick_up.md) the berries from beside the bush and stores them in the depot. A spot that lost its bush is planted again from the depot. Once the depot holds three berries, a cell's worth, the next round builds the next cell with them.

A bush slows and hurts whatever moves through it, the bot too, so its paths never enter one, as they keep out of fire and cactus, and `pick_up` reaches into a bush for berries lying there rather than walk in. The block of path between cells keeps every bush in reach without stepping into one.
