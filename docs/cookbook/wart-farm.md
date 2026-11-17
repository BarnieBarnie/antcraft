# Grow a nether wart farm

The job `jobs/wart_farm` grows a nether wart farm, the base of every potion, and farms it until a player stops it. Wart needs no water and grows in any dimension, so the farm can stand in the overworld: the bot does not go to the nether, but takes the soul sand and wart a player brought back from a chest. Each time the bot has or can get the wart and soul sand one more cell takes, it builds it; the wart comes from its own harvest, the soul sand from the chests. It is a spec of [`lib/cellfarm`](../reference/modules/lib/cellfarm.md) (#834), the engine the [wood farm](wood-farm.md) and the [bamboo farm](bamboo-farm.md) run on too, so its layout, depot, growth and rounds are the same; it is one [`lib/project`](../reference/modules/lib/project.md), so a stopped run goes on where it was.

```text
/antcraft bot run jobs.wart_farm
/antcraft bot run jobs.wart_farm 10 64 10
/antcraft bot run jobs.wart_farm 10 64 10 4 16
```

The arguments are the farm's corner `x y z` (by default where the bot stands, or where the last run started; another corner moves the farm there), the most cells it grows to (by default as many as keep growing while the bot stands at its corner, [`limit`](../reference/modules/lib/cellfarm.md#limitf-name-size)) and how far to look for what it needs, in blocks (default 32). The bot says `built cell 2` and `cells 2/12` as the farm grows, and `farm full` once it has all its cells.

## A cell

A cell is three floor blocks in a row going east, the floor being the block under the corner, each soul sand with wart on it.

| What | Where, from the cell's low corner |
|---|---|
| soul sand, wart on it | `(0, 0, 0)`, `(1, 0, 0)` and `(2, 0, 0)` |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from the corner `(0, 0, -2)` going east |

To build one, the bot clears the blocks over the floor, mines each spot that is not soul sand (such as stone, with a pickaxe it gets), puts soul sand there and plants three wart. A cell takes 3 wart and 3 soul sand, from what the bot carries or the chests hold. What the build dug goes in the depot.

## The rounds

Each round, wart of age 3, ripe, is mined, which drops 2 to 4 wart, picked up; the bot plants one again on its spot and stores the rest in the depot. A spot that lost its wart is planted again from the depot. Once the depot holds three wart and the chests three soul sand, a cell's worth, the next round builds the next cell with them. Wart ripens slowly, in some 40000 ticks; a round that harvested nothing waits in the corner's chunk, where the cells get the random ticks they grow on.
