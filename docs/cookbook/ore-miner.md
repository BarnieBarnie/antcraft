# Mine ore for home

The job `jobs/ore_miner` has one task: gather one ore and keep it at home (#1002). Iron first: the bot digs down from its home, branch-mines its home chunk for iron ore, smelts the raw iron at home and stores the ingots in chests it places there. Unlike [`jobs.strip_mine`](strip-mine.md), which stops once the bot carries enough, it goes on until the chunk's level is mined out, making as many trips as that takes.

```text
/antcraft bot run jobs.ore_miner
/antcraft bot run jobs.ore_miner raw_iron 1 32
```

The words are the ore, a raw item of [`lib.dig`](../reference/modules/lib/dig.md) such as `raw_iron` (the default), `raw_copper` or `coal`; how many chunks it may mine, 1 (taken for later: only the home chunk is mined for now); and how far to look for chests and the kit, 32 blocks (at most 32). It mines at the height where the ore is most common ([`best_y`](../reference/modules/lib/dig.md)), y 16 for iron. Emerald has no such height, so the job says `no_ore` for it.

## The trips

The bot starts with the miner's kit of `jobs.strip_mine`, a stone pickaxe, a crafting table and 16 torches ([`lib.bootstrap.kit`](../reference/modules/lib/bootstrap.md)), at home. Each trip:

| Step | How |
|---|---|
| eat | [`lib.food.upkeep`](../reference/modules/lib/food.md), at home |
| pickaxe | [`lib.get.ensure_tool`](../reference/modules/lib/get.md): one that lasts the stairs and a branch, of the tier the ore needs, stone at least |
| down | the first time, stairs from home down to the ore's height, setting off north and turning at the chunk's edge ([`lib.dig.stairs_to`](../reference/modules/lib/dig.md)); after that it walks down the stairs it dug |
| branches | from a corridor through the bottom of the stairs, square to their last steps, a branch each way at every third block across the chunk, each to the chunk's edge ([`lib.dig.branch`](../reference/modules/lib/dig.md)), mining the ore in its walls and ceiling and putting a torch every 8 blocks |
| home | when it has fewer than 3 empty slots, when its pickaxe would not last another branch, or when every branch is dug, it walks back up the stairs and home |
| smelt | it smelts the ore it carries with [`lib.craft.smelt_here`](../reference/modules/lib/craft.md), in the furnace it carries or one it crafts from the cobblestone it dug, burning coal; where it carries too little coal (one smelts 8), it gets more with [`lib.get`](../reference/modules/lib/get.md), from its chests first |
| store | all it carries goes into its depot (below) but its pickaxe, crafting table, furnace and torches |

Branches 3 blocks apart leave no block of the level unseen: each branch's walls are the blocks beside it, so the blocks between two branches are in the wall of one or the other. The two columns just behind the bottom of the stairs are left, as a branch through them would dig out the last steps.

Once every branch is dug the job prints `ore miner: dug the chunk` and ends with the bot at home. Where something stops it, it warns `ore miner:` with the reason: a pickaxe it cannot get, a step it could not dig, `full` where it stays nearly full after storing, or a chest it cannot get for the depot (`ore miner: no chest has room for [...] and none to get: <reason>`).

## The depot

The bot keeps what it brings home in a [`lib.depot`](../reference/modules/lib/depot.md) named `ore`: a row of chests that starts one block south of its home, behind the stairs, and runs east. Each kind has its own chests:

| Category | What |
|---|---|
| `ingots` | `#c:ingots`: what it smelted |
| `ores` | `#c:raw_materials` and `#c:ores`: raw items it could not smelt, ore blocks |
| `other` | the rest: cobblestone, coal, and whatever else it dug |

A chest comes from [`lib.get`](../reference/modules/lib/get.md): carried, from a chest within the radius, or crafted from planks or logs.

## Where it is

The job keeps its progress in `memory["ore_miner"]`: the chunk and height it mines, the bottom of its stairs and the branches it has dug (`done`, as `"<column> <facing>"`). A bot that is stopped, or the server restarted, goes back down the same stairs and on with the branches it has not dug; run again on a chunk it has dug, it ends at once. Mining another chunk or height starts afresh.

## In a script

```python
import jobs.ore_miner
jobs.ore_miner.run("raw_iron")
```

`run(ore="raw_iron", chunks=1, radius=32, y=None, area=None)` gives the result that ended it: `ok` once the chunk is dug. `y` is the height to mine at, the ore's best height where `None`; `area` the columns to mine in place of the home chunk, as `(x0, z0, x1, z1)`, the corners included, for a smaller area or one elsewhere.
