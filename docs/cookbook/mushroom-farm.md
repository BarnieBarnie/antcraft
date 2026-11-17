# Grow a mushroom farm

The job `jobs/mushroom_farm` grows a mushroom farm, brown and red mushrooms for mushroom stew, and farms it until a player stops it. Mushrooms spread on their own: each cell keeps a brown and a red one, and the bot harvests what they spread onto. The cells are podzol, which a mushroom grows on in any light, so the farm needs no roof or dark room and can stand in the open. Each time the bot has or can get the podzol and mushrooms one more cell takes, it builds it; the mushrooms come from its own harvest, the podzol from the chests. It is a spec of [`lib/cellfarm`](../reference/modules/lib/cellfarm.md) (#836), the engine the [wood farm](wood-farm.md) and the [wart farm](wart-farm.md) run on too, so its layout, depot, growth and rounds are the same; it is one [`lib/project`](../reference/modules/lib/project.md), so a stopped run goes on where it was.

```text
/antcraft bot run jobs.mushroom_farm
/antcraft bot run jobs.mushroom_farm 10 64 10
/antcraft bot run jobs.mushroom_farm 10 64 10 4 16
```

The arguments are the farm's corner `x y z` (by default where the bot stands, or where the last run started; another corner moves the farm there), the most cells it grows to (by default as many as keep growing while the bot stands at its corner, [`limit`](../reference/modules/lib/cellfarm.md#limitf-name-size)) and how far to look for what it needs, in blocks (default 32). The bot says `built cell 2` and `cells 2/12` as the farm grows, and `farm full` once it has all its cells.

## A cell

A cell is three floor blocks in a row going east, the floor being the block under the corner, each podzol (mycelium or nylium already there is kept).

| What | Where, from the cell's low corner |
|---|---|
| podzol, a brown mushroom on it | `(0, 0, 0)` |
| podzol, where the mushrooms spread to | `(1, 0, 0)` |
| podzol, a red mushroom on it | `(2, 0, 0)` |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from the corner `(0, 0, -2)` going east |

To build one, the bot clears the blocks over the floor, mines each spot that is not podzol (such as stone, with a pickaxe it gets), puts podzol there and plants the two mushrooms. A cell takes 3 podzol, a brown and a red mushroom, from what the bot carries or the chests hold. Podzol drops dirt when mined without Silk Touch, so a player brings it. What the build dug goes in the depot.

## The rounds

Each round, a mushroom that spread onto a cell's middle spot is mined and stored in the depot; the brown and red ones stay to spread again. One of those that is gone is planted again from the depot. Once the depot holds a brown and a red mushroom and the chests three podzol, a cell's worth, the next round builds the next cell with them. A mushroom spreads slowly, trying on some 1 in 34000 ticks, and no further where five of its kind stand within 4 blocks; a round that harvested nothing waits in the corner's chunk, where the cells get the random ticks they grow on. Where the farm stands in the dark (light 12 or below), mushrooms also spread onto the stone round the cells, and the bot leaves those.

Not yet done: bone meal on a mushroom grows a huge mushroom, a renewable source of mushroom blocks to fell.
