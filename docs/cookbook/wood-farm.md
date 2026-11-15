# Grow a wood farm

The job `jobs/wood_farm` starts a wood farm with an empty-handed bot and farms it until a player stops it (the stop command or the bot's screen). It builds one tree cell, and each time it has or can get what another cell takes, it builds the next one, up to the size at which the bot is always busy. It is one [`lib/project`](../reference/modules/lib/project.md), so a run stopped halfway (a stop, a reload, a death) goes on where it was when it is run again, on the same farm. Its home is the path by the depot, `(0, 0, -1)` from the corner ([`set_home`](../reference/movement/set_home.md)), so a bot that dies, such as by a fall off its scaffold, comes back at the farm. Its script starts again from the top there, where [`lib.trees.recover`](../reference/modules/lib/trees.md#recover) sees the death in [`deaths()`](../reference/bot/deaths.md), takes back the scaffold it died on ([`lib.trees.take_back`](../reference/modules/lib/trees.md#take_backkeep)), picks up what it dropped round [`last_death()`](../reference/bot/last_death.md), and the job goes on with the step it was on. A death costs it the respawn and the walk back ([`lib.cost.death`](../reference/modules/lib/cost.md#deathat-startnone)) and nothing it can pick up again, so a bot stranded up a tree with no other way down drops, even to its death.

```text
/antcraft bot run jobs.wood_farm birch_sapling
/antcraft bot run jobs.wood_farm oak_sapling 10 64 10
/antcraft bot run jobs.wood_farm spruce_sapling 10 64 10 12 16
```

The arguments are the kind, one of [`lib.treefarm.KINDS`](../reference/modules/lib/treefarm.md): a sapling (oak, birch, spruce, jungle, dark oak, pale oak, acacia, cherry or poplar), `mangrove_propagule` or `giant_spruce` or `giant_jungle`, which grow from four spruce or jungle saplings; the farm's first corner `x y z` (by default where the bot stands, or where the last farm of that kind started), the most cells it grows to (by default `most` of the ticks a tree takes, which the farm learns, see below) and how far to look for what it needs, in blocks (default 32). The bot says one line per step it starts, `birch_farm 3/4: cell`, `built cell 2` and `cells 2/47` as the farm grows, and `farm full` once it has all its cells. A full farm that learns it may grow (see How big) says its new limit, `cells 3/5`, and builds on.

The farm, from the corner `x y z` (the cells' floor is the block under it):

| What | Where, from the corner |
|---|---|
| cell 1, as wide and deep as the sapling's cell (5 for birch, 7 for oak) | `(0, -1, 0)` east and south |
| the next cells | 4 to a row going east, rows going south, a block of path between cells ([`lib.cells`](../reference/modules/lib/cells.md)) |
| the depot, a row of chests added as they fill ([`lib.depot`](../reference/modules/lib/depot.md)) | from `(0, 0, -2)` going east |

The steps, from `config/antcraft/scripts/jobs/wood_farm.py`:

```python
steps = [Step("kit", lib.bootstrap.kit, (kit(kind), radius)),
         Step("sapling", get, (lib.treefarm.sapling(kind), 2 * n, radius)),
         Step("cell", first_cell, (kind, name, size, radius)),
         Step("farm", farm, (kind, name, size, radius))]
r = lib.project.run(name, steps)
```

## How it works

- **kit**: [`lib.bootstrap.kit`](../reference/modules/lib/bootstrap.md) of a stone axe and 16 dirt, to put under the saplings and to climb a tall tree with; for a giant as much dirt as its cell's `clear` height, 31 for a giant spruce and 33 for a giant jungle (`scaffold(kind)`), as a pillar beside its trunk of up to 29 takes that many (#658). Each comes through [`lib.get`](../reference/modules/lib/get.md): from a chest within the radius, else gathered and crafted. The farm keeps what it takes.
- **sapling**: two saplings a spot (four spots for dark oak, pale oak and the giants), one to plant and one to replant with, by `get`: from a chest, else broken out of the leaves of a tree of the kind.
- **cell**: what [`lib.treefarm.materials`](../reference/modules/lib/treefarm.md) lists for a cell (dirt, the sapling, 2 torches, slabs for an oak's roof) by `get`, the tools [`lib.terrain.needs`](../reference/modules/lib/terrain.md) names for its ground and for the ground under each sapling spot that is not dirt (a pickaxe on stone, where the dirt goes) by `ensure_tool`, then `lib.treefarm.build_cell` at cell 1. Its dirt, here and for the next cells and the scaffold of a felling tried again, comes through `lib.treefarm.get_outside`, which digs none from under a cell of the farm (#767, #768). The roof takes any wooden slabs the chests within the radius hold, such as birch slabs for an oak farm; those they lack it makes as oak slabs, as `get` takes an item, not a tag.
- **farm**, round after round, for ever:
    1. fells each grown tree with `lib.treefarm.harvest`, once the bot has `free(kind)` empty slots for its drops (stacks for twice the kind's mean logs, `LOGS`, and 4 more for saplings, sticks, apples and dirt, 2 more for a mangrove's roots: 5 for a birch, 7 for a giant, #766): with fewer it stores what it carries first (`lib.depot.room`), and where that leaves too few (the depot full and its row blocked), it fells nothing, so no drop lands on the ground, says once `inventory full, store: blocked; felling stops` and waits, trying again next round (#765); which replants it, getting the saplings it lacks for that (a 2x2 kind takes four); where a felling leaves a log (out of reach, or no room for the drops), it says so once, such as `harvest: out_of_reach at Pos(8, 70, 4)`, gets the kit's dirt (`scaffold(kind)`) to pillar with and an axe, and fells it again. A cell with a log anywhere in its tree space, the cell up to its `clear` height, counts as grown (a branch an acacia or cherry felling left off the trunk too), so a felling that fails again is tried again next round;
    2. stores what it carries with `lib.depot.store`, every round, one that fells nothing too, so what builds up between fellings goes in the depot (#766): logs, saplings of other kinds, sticks and apples each in their own chests, the rest in `other`; the bot keeps its saplings, torches, slabs, the crafting table it makes chests with and a stack of dirt (`dirt(kind)`: at least `STACK`, 64, or its scaffold and a cell's dirt where that is more), and stores the rest of its dirt, such as what flattening a cell and its walks dug, so it never fills its inventory (#762). Where the depot's chests are full, `lib.depot.store` adds a chest to its row, made of the logs; where it cannot (the row blocked, no chest to be got), the farm says so once for each reason, such as `store: blocked, kept {'minecraft:birch_log': 20}`, and goes on with the rest carried;
    3. tends each cell: a sapling on a spot that holds none ([`lib.treefarm.plant`](../reference/modules/lib/treefarm.md), which first puts back dirt a path dug out under it, getting a dirt it lacks; a spot it cannot plant it says once, such as `plant: no_item at Pos(8, 70, 4)`, #764), but never under logs of the cell's tree left standing (from the chests, else broken out of leaves of the kind, such as those of the trees it just felled), a torch where one is gone, standing or on a wall (a walk that pillars where a torch stands breaks it with its drop, and the bot picks the torch up to put back);
    4. builds the next cell when the farm has fewer than its most and the bot has what a cell takes and a spare sapling: what it lacks it takes from the chests within the radius, else gets with `get` as for the first cell (torches crafted from coal or charcoal of its logs, dirt dug, saplings from leaves, oak slabs where the chests hold no wooden slabs). Where `get` fails, it says once what it lacks, such as `next cell: no_item, lacks {'minecraft:torch': 2}`, and tries again 10 rounds later (`BACKOFF`, some 1000 ticks of waits), or at once in a round that felled a tree, whose logs may make what it lacks: a `get` can be a long chain, such as charcoal for torches, which takes a furnace, cobblestone and a pickaxe;
    5. where it felled nothing, picks up the saplings, sticks and apples lying in the farm (the leaves of a felled tree decay over the next minutes and drop them then);
    6. takes back the scaffold its walks placed in the round with `lib.trees.take_back`, such as a pillar up to a drop on leaves or up to leaves high up for a sapling, then, where it felled nothing, waits 100 ticks. A felling (`lib.treefarm.harvest`) and a build (`build_cell`) take back their own and leave what earlier walks placed standing, still listed (#657), so the round takes that back before each of them too, out of their way (#654).
- `lib.project.run` keeps the steps done in `memory["projects"]`, `lib.cells` the cells built in `memory["cells"]`, `lib.depot` the chests in `memory["depots"]` and the job the corner in `memory["wood_farms"]`, so a farm run again goes on farming: it does not build its first cell a second time. To start over, `lib.project.reset("birch_farm")` and pop the farm from those.

## How big

A farm is as big as it is useful: one cell more than the bot can harvest while the first one grows back would only stand waiting. So the most cells is a sapling's growth time over the time the bot takes for one tree:

```python
GROWTH = 2 * 7 * 4096 / 3                        # some 19,115 ticks, 16 minutes
HARVEST["minecraft:birch_sapling"] == 400        # fell, replant and store one birch
most(harvest(kind)) == GROWTH // harvest(kind)   # 47 cells of birch
```

A sapling grows on random ticks: each tick, the game gives one to `randomTickSpeed` (3) blocks of each 16 by 16 by 16 section, 4096 blocks, and a sapling with light 9 or more over it moves up a stage on 1 in 7 of those, from stage 0 to 1, then from 1 to a tree. Every sapling of `KINDS` grows the same way.

`harvest(kind)` is the ticks one tree takes the bot. The farm learns it: in each round that fells a tree it times, with [`game_time()`](../reference/world/game_time.md), the felling, storing and replanting, and keeps the ticks of its last 10 trees (`WINDOW`) in `memory["wood_ticks"]`, each tree of a round their mean. Once it has felled 3 trees (`SAMPLES`), `harvest` is the mean of those it keeps, so old slow trees, such as those it got an axe or dirt for, fade out, and a farm whose trees take longer than its kind's estimate stops growing sooner, and one that is quicker grows on. Until then it is `HARVEST[kind]`, which gives the ticks for one tree of each kind. The birch's is measured in `WoodFarmGameTest`, where felling, replanting and storing a birch took some 150 to 1000 ticks. The other kinds are measured too, each the mean of 11 trees grown from fixed seeds in a farm of one cell (#769), and the giants in `WoodFarmGameTest` (#669, #677):

| Kind | Mean logs | `HARVEST` | `most` |
|---|---|---|---|
| oak | 5 (measured: some 165 to 300 ticks) | 200 | 95 |
| birch | 6 | 400 | 47 |
| spruce | 6.5 (some 250 to 450) | 350 | 54 |
| jungle | 8.5 (some 175 to 550) | 350 | 54 |
| acacia | 8 (some 200 to 450) | 300 | 63 |
| cherry | 16 (some 400 to 1250) | 850 | 22 |
| poplar | 15 (measured in the game; some 400 to 1000 ticks) | 600 | 31 |
| mangrove | 16 (measured in the game: felled and its roots mined, some 250 to 3550 ticks, 1400 on average) | 1400 | 13 |
| dark oak | 40.5 (some 850 to 1500) | 1250 | 15 |
| pale oak | 40.5 (some 650 to 1200) | 950 | 20 |
| giant spruce | 84 (measured: a trunk of 24 took some 3050 to 3300 ticks) | 3200 | 5 |
| giant jungle | 87 (measured: 67 logs took some 2000 to 2950 ticks, 2250 on average, with no death) | 2250 | 8 |

Pass the most cells as an argument for a farm of another size: the farm keeps to it, whatever it learns.

## A forester

The job `jobs/forester` picks the tree kind itself and starts its farm where the bot stands:

```
/antcraft bot run jobs.forester
/antcraft bot run jobs.forester 16
```

The argument is how far to look, in blocks (32 by default); the farm gets the same radius. Its candidates are the kinds of `lib.treefarm.KINDS`:

1. kinds whose saplings a chest within the radius holds (`lib.stock`), as the farm can start on them at once;
2. then kinds of a tree within the radius, whose leaves give the saplings: `jobs.forester.trees(radius)` finds the nearest tree of each kind in one scan of the logs, with `lib.trees.next_tree`'s checks.

Within each, the simplest cell comes first: a 1x1 trunk before a 2x2 (dark oak, pale oak, and the giants, whose spruce or jungle saplings come second to the one-sapling kind of the same saplings), no roof before the roof of oak, a smaller cell, then a shorter clear; ties go to the nearest. So spruce saplings in a chest beat a birch tree nearby, and among trees birch comes before jungle, spruce, oak and dark oak. It says its choice and why, such as `forester: spruce_sapling, saplings in a chest 3 blocks away`, then runs `jobs.wood_farm.run` for that kind, which farms until stopped. With no candidate it warns `no_saplings` and stops. Imported, it runs nothing: `jobs.forester.candidates(radius)` gives the ranked `(kind, why)` pairs and `jobs.forester.run(radius)` starts the farm.

`ForesterGameTest` checks that it chooses stored spruce saplings over a birch nearby, a birch without stored saplings, stored birch saplings over oak ones in a nearer chest, a birch over a nearer oak, and warns `no_saplings` with nothing around.

## Use it from a script

Imported, the job runs nothing, so another job starts a farm with its `run`:

```python
import jobs.wood_farm

jobs.wood_farm.run("minecraft:spruce_sapling", Pos(10, 64, 10), 12)
```

`run(kind, at=None, size=None, radius=32)` never returns but when a setup step fails: it gives `lib.project.run`'s `result()` then. `most(harvest)` gives the most cells for a harvest time of `harvest` ticks.

A script, or a test, can swap a function the job calls by assigning it, as for any module (see [Modules & import](../language/modules.md)): `jobs.wood_farm.wait = counted`, or `lib.cells.built = lambda name: [0]` with `lib.cells.cell` and `lib.cells.count`, for a farm of one cell not laid out. The job's functions read `lib.cells.built` and the rest when they call them, so they see the new ones. What is not swapped still reads the farm in `memory`: building the next cell calls `lib.cells.next` and `lib.cells.add`, which fail with `no farm birch_farm: lay it out first` where no `lib.cells.layout` ran, so swap those too, or `stocked`, to keep it from building.

`WoodFarmGameTest` runs the job for birch in a 24 by 16 by 24 area with a stone axe, dirt, saplings and torches in a chest: the bot builds and plants the first cell, the test puts a small birch in its sapling's place, and the bot fells the birch, replants and stores the logs in a chest of the depot. On the area's stone floor it makes a pickaxe to put the dirt under the sapling, and an oak farm roofs its cell with birch slabs from the chest. With two more saplings and torches in the chest it builds a second cell, and with a coal and a log instead of the torches it makes them for it; stopped as it starts farming and run again, it goes on with the same farm. Where it cannot get what the next cell takes, it does not try every round, and a birch log left off the trunk is felled. Killed up on its scaffold as it fells a tall birch, the bot comes back at the farm, takes back the scaffold, picks up what it dropped and finishes the cell; first spawned up a bedrock pillar with nothing to climb down with, it drops to its death and builds the farm after it comes back. A giant spruce farm, in a 24 by 40 by 24 area, builds its cell of four saplings, and the bot fells the giant grown there (a trunk of 24), replants the four spots and stores the logs: some 3050 to 3300 ticks from the growth, taking its scaffold back as it comes down from stand to stand (#677, #715, #761). The tallest giants, a trunk of 28 or 29, take some 4500 to 4800 ticks. A giant jungle farm does the same for a giant jungle of 67 logs (a trunk of 16 and its side branches): some 2000 to 2950 ticks from the growth; killed 8 blocks up the tree, the bot comes back, takes back its scaffold, picks up its drops and finishes it in some 2300 to 3400, up to 3750 with 36 giant farms at once sharing the scan and path budgets (#715, #761, #769). `HARVEST["giant_jungle"]` is 2250, the mean with no death. One of 89 logs (a trunk of 20) failed a third of its runs, the bot falling to its death or leaving a branch log (#668); `lib.treefarm.harvest` now fells it whole in some 2500 to 3800 ticks, the bot coming back after the odd fatal fall (#694, #695). Taller ones, which killed it by falls each time (#668), now fell with no death (#715): a trunk of 21 in some 3450 ticks, 24 in some 3850 and 28 in some 4050 to 6950 (#731), too slow for a farm test of its own.
