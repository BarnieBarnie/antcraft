# Grow a wood farm

The job `jobs/wood_farm` starts a wood farm with an empty-handed bot and farms it until a player stops it (the stop command or the bot's screen). It builds one tree cell, and each time the chests hold what another cell takes, it builds the next one, up to the size at which the bot is always busy. It is one [`lib/project`](../reference/modules/lib/project.md), so a run stopped halfway (a stop, a reload, a death) goes on where it was when it is run again, on the same farm.

```text
/antcraft bot run jobs.wood_farm birch_sapling
/antcraft bot run jobs.wood_farm oak_sapling 10 64 10
/antcraft bot run jobs.wood_farm spruce_sapling 10 64 10 12 16
```

The arguments are the sapling, one of [`lib.treefarm.KINDS`](../reference/modules/lib/treefarm.md) (oak, birch, spruce, jungle or dark oak), the farm's first corner `x y z` (by default where the bot stands, or where the last farm of that sapling started), the most cells it grows to (by default `most()`, see below) and how far to look for what it needs, in blocks (default 32). The bot says one line per step it starts, `birch_farm 3/4: cell`, `built cell 2` and `cells 2/47` as the farm grows, and `farm full` once it has all its cells.

The farm, from the corner `x y z` (the cells' floor is the block under it):

| What | Where, from the corner |
|---|---|
| cell 1, as wide and deep as the sapling's cell (5 for birch, 7 for oak) | `(0, -1, 0)` east and south |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from `(0, 0, -2)` going east |

The steps, from `config/antcraft/scripts/jobs/wood_farm.py`:

```python
steps = [Step("kit", lib.bootstrap.kit, (KIT, radius)),
         Step("sapling", get, (kind, 2 * n, radius)),
         Step("cell", first_cell, (kind, name, size, radius)),
         Step("farm", farm, (kind, name, size, radius))]
r = lib.project.run(name, steps)
```

## How it works

- **kit**: [`lib.bootstrap.kit`](../reference/modules/lib/bootstrap.md) of a stone axe and 16 dirt, to put under the saplings and to climb a tall tree with. Each comes through [`lib.get`](../reference/modules/lib/get.md): from a chest within the radius, else gathered and crafted. The farm keeps what it takes.
- **sapling**: two saplings a spot (four spots for dark oak), one to plant and one to replant with, by `get`: from a chest, else broken out of the leaves of a tree of the kind.
- **cell**: what [`lib.treefarm.materials`](../reference/modules/lib/treefarm.md) lists for a cell (dirt, the sapling, 2 torches, oak slabs for an oak's roof) by `get`, the tools [`lib.terrain.needs`](../reference/modules/lib/terrain.md) names for its ground by `ensure_tool`, then `lib.treefarm.build_cell` at cell 1.
- **farm**, round after round, for ever:
    1. fells each grown tree with `lib.treefarm.harvest`, which replants it;
    2. stores what it felled with `lib.depot.store`: logs, saplings of other kinds, sticks and apples each in their own chests, the rest in `other`; the bot keeps its saplings, dirt, torches, slabs and the crafting table it makes chests with;
    3. tends each cell: a sapling on a spot that holds none (from the chests, else broken out of leaves of the kind, such as those of the trees it just felled), a torch where one is gone (a walk that pillars where a torch stands takes its place, so a lost torch comes from the chests);
    4. builds the next cell when the farm has fewer than its most and the bot carries, or takes from the chests within the radius, what a cell takes and a spare sapling;
    5. waits 100 ticks where nothing had grown.
- `lib.project.run` keeps the steps done in `memory["projects"]`, `lib.cells` the cells built in `memory["cells"]`, `lib.depot` the chests in `memory["depots"]` and the job the corner in `memory["wood_farms"]`, so a farm run again goes on farming: it does not build its first cell a second time. To start over, `lib.project.reset("birch_farm")` and pop the farm from those.

## How big

A farm is as big as it is useful: one cell more than the bot can harvest while the first one grows back would only stand waiting. So the most cells is a sapling's growth time over the time the bot takes for one tree:

```python
GROWTH = 2 * 7 * 4096 / 3   # some 19,115 ticks, 16 minutes
HARVEST = 400               # fell, replant and store one birch
most() == GROWTH // HARVEST # 47 cells
```

A sapling grows on random ticks: each tick, the game gives one to `randomTickSpeed` (3) blocks of each 16 by 16 by 16 section, 4096 blocks, and a sapling with light 9 or more over it moves up a stage on 1 in 7 of those, from stage 0 to 1, then from 1 to a tree. Every sapling of `KINDS` grows the same way. `HARVEST` is measured in `WoodFarmGameTest`, where felling, replanting and storing a birch takes some 150 to 1000 ticks; pass the most cells as an argument for a farm of another size.

## A forester

The job `jobs/forester` picks the tree kind itself and starts its farm where the bot stands:

```
/antcraft bot run jobs.forester
/antcraft bot run jobs.forester 16
```

The argument is how far to look, in blocks (32 by default); the farm gets the same radius. Its candidates are the kinds of `lib.treefarm.KINDS`:

1. kinds whose saplings a chest within the radius holds (`lib.stock`), as the farm can start on them at once;
2. then kinds of a tree within the radius (`lib.trees.next_tree`), whose leaves give the saplings.

Within each, the simplest cell comes first: a 1x1 trunk before the 2x2 of dark oak, no roof before the roof of oak, a smaller cell, then a shorter clear; ties go to the nearest. So spruce saplings in a chest beat a birch tree nearby, and among trees birch comes before jungle, spruce, oak and dark oak. It says its choice and why, such as `forester: spruce_sapling, saplings in a chest 3 blocks away`, then runs `jobs.wood_farm.run` for that kind, which farms until stopped. With no candidate it warns `no_saplings` and stops. Imported, it runs nothing: `jobs.forester.candidates(radius)` gives the ranked `(kind, why)` pairs and `jobs.forester.run(radius)` starts the farm.

`ForesterGameTest` checks that it chooses stored spruce saplings over a birch nearby, a birch without stored saplings, and warns `no_saplings` with nothing around.

## Use it from a script

Imported, the job runs nothing, so another job starts a farm with its `run`:

```python
import jobs.wood_farm

jobs.wood_farm.run("minecraft:spruce_sapling", Pos(10, 64, 10), 12)
```

`run(kind, at=None, size=None, radius=32)` never returns but when a setup step fails: it gives `lib.project.run`'s `result()` then. `most(harvest)` gives the most cells for a harvest time of `harvest` ticks.

`WoodFarmGameTest` runs the job for birch in a 24 by 16 by 24 area with a stone axe, dirt and torches in a chest and a small birch whose leaves give the saplings: the bot builds and plants the first cell, the test grows its sapling, and the bot fells the birch, replants and stores the logs in a chest of the depot. With two more saplings and torches in the chest it builds a second cell; stopped as it starts farming and run again, it goes on with the same farm.
