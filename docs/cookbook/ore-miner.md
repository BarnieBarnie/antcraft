# Mine ore for home

The job `jobs/ore_miner` has one task: gather one ore and keep it at home (#1002). The bot digs down from its home, branch-mines the chunks round it for the ore, smelts it at home where it smelts, and stores what it got in chests it places there. Run one bot per ore you want (#1005). Unlike [`jobs.strip_mine`](strip-mine.md), which stops once the bot carries enough, it goes on until the level of every chunk of its claim is mined out, making as many trips as that takes, and then says it is finished.

```text
/antcraft bot run jobs.ore_miner
/antcraft bot run jobs.ore_miner raw_iron 9 32
```

The words are the ore, an item of [`lib.dig`](../reference/modules/lib/dig.md)'s `ORES` (below), `raw_iron` by default; how many chunks it claims, 9 (below); and how far to look for chests and the kit, 32 blocks (at most 32). It mines at the height where the ore is most common ([`best_y`](../reference/modules/lib/dig.md)). Where that height is at or above home, as coal's 96 often is, it mines 8 blocks below home instead (`DEPTH`), so it still digs stairs and mines underground. Emerald has no such height, so the job says `no_ore` for it.

| Ore | Height | Pickaxe | Kept at home as |
|---|---|---|---|
| `coal` | y 96, or 8 below home where home is lower | stone | coal |
| `raw_copper` | y 48 | stone | copper ingots |
| `raw_iron` | y 16 | stone | iron ingots |
| `lapis_lazuli` | y 0 | stone | lapis lazuli |
| `raw_gold` | y -16 | iron | gold ingots |
| `redstone` | y -59 | iron | redstone |
| `diamond` | y -59 | iron | diamonds |

Raw copper, iron and gold are smelted to ingots; the rest is stored as it drops, so a coal miner needs no fuel. The pickaxe is the lowest tier that drops the ore ([`lib.tools.NEEDS`](../reference/modules/lib/tools.md)), stone at least: a gold, redstone or diamond miner with only a stone pickaxe makes or fetches an iron one before it goes down.

## The trips

The bot starts with the miner's kit of `jobs.strip_mine`, a stone pickaxe, a crafting table and 16 torches ([`lib.bootstrap.kit`](../reference/modules/lib/bootstrap.md)), at home. Each trip:

| Step | How |
|---|---|
| eat | [`lib.food.upkeep`](../reference/modules/lib/food.md) at home; between branches only food it carries ([`eat_if_hungry`](../reference/modules/lib/food.md)), so it never walks off for food underground (#1008) |
| pickaxe | [`lib.get.ensure_tool`](../reference/modules/lib/get.md): one that lasts the stairs and a branch, of the tier the ore needs, stone at least |
| down | the first time, stairs from home down to the ore's height, setting off north and turning clockwise at the home chunk's edge ([`lib.dig.spiral_to`](../reference/modules/lib/dig.md#spiral_toy-facingnorth)), so they spiral down round the chunk as deep as they must; after that it walks down the stairs it dug, and along its tunnels to the chunk it mines, by waypoints: the corner of each turn of the stairs, their bottom, and the start and end of each tunnel, one short walk to each in turn. Where one fails the job ends with that waypoint as `at` |
| branches | from a corridor through the bottom of the stairs, square to their last steps, a branch each way at every third block across the chunk, each to the chunk's edge ([`lib.dig.branch`](../reference/modules/lib/dig.md)), mining the ore in its walls and ceiling and putting a torch every 8 blocks |
| next chunk | with every branch of a chunk dug, it tunnels on at the same level into the next chunk of its claim (below) and digs that one's grid from a corridor through the tunnel's end |
| home | when it has fewer than 3 empty slots, when its pickaxe would not last another branch, or when its claim is mined out, it walks back by the same waypoints, from the last one it reached, up the stairs and home (#1016) |
| smelt | raw copper, iron or gold only: it smelts the ore it carries with [`lib.craft.smelt_here`](../reference/modules/lib/craft.md), in the furnace it carries or one it crafts from the cobblestone it dug, burning coal, charcoal, logs or planks (coal or charcoal smelts 8, a log or plank 1.5); where it carries too little it takes more from its chests in that order, coal first, such as the coal it mined and stored, but never mines or crafts any. What its fuel cannot smelt it stores raw and warns once, until it has fuel for all again (#1008, #1012), and on a later visit home with fuel it takes that raw ore back from its depot and smelts it too |
| better pickaxe | where a chest within the radius, its depot's or any other, holds a better pickaxe than its own (below), it takes that one and holds it |
| store | all it carries goes into its depot (below) but its pickaxe, crafting table, furnace and torches; after taking a better pickaxe, its old ones go too |

Before its first trip, and on each trip home, it looks for a better pickaxe with [`lib.enchant.best_pickaxe`](../reference/modules/lib/enchant.md): a higher tier first, then better enchantments (Fortune counts most, then Efficiency and Mending, then Unbreaking), then more uses left. A pickaxe below the tier the ore needs, such as a golden one for iron, is never taken, nor one no better than its own. So an owner, or another bot, that puts a Fortune III pickaxe in one of its chests has it mining with that one from its next trip; it takes only that pickaxe, leaving the other ones in the chest, and its old pickaxe goes into the depot.

Branches 3 blocks apart leave no block of the level unseen: each branch's walls are the blocks beside it, so the blocks between two branches are in the wall of one or the other. The floors of the steps of the stairs are left, as a branch through them, or a vein mined from its ceiling, would dig out those steps.

## The claim

An ore miner has to go further from home to keep finding ore, but not forever: it works a claim of `chunks` chunks, 9 by default, starting with its home chunk and spiralling out clockwise from it (east, south, west, west, north, north, east, east), so 9 is the 3 by 3 chunks round home. Only the home chunk has stairs: there is one staircase, from home, and the bot reaches every other chunk by a tunnel at the ore's height from the chunk before it, which is cheaper than walking over the surface and digging new stairs in each. The tunnel runs on from the end of the corridor where the next chunk lies along it, else along the branch farthest from the stairs.

With its whole claim mined out, the bot goes home and says

```text
ore_miner raw_iron: finished my 9 chunks around <x> <y> <z>, move me or give me a new job
```

and the job ends `ok`, its result's `finished` true. Run again, it says the same at once and stops, mining nothing. To have it mine on, move it: a bot with another home makes a new claim round that one. Running it with a larger `chunks` goes on with the chunks of the claim it has not mined.

Where something stops it, it warns `ore miner:` with the reason: a pickaxe it cannot get, a step it could not dig, `full` where it stays nearly full after storing, or a chest it cannot get for the depot (`ore miner: no chest has room for [...] and none to get: <reason>`).

## The depot

The bot keeps what it brings home in a [`lib.depot`](../reference/modules/lib/depot.md) named `ore`: a row of chests that starts one block south of its home, behind the stairs, and runs east. Each kind has its own chests:

| Category | What |
|---|---|
| `ingots` | `#c:ingots`: what it smelted |
| `gems` | `#c:gems`, `#c:dusts` and coal: diamonds, lapis lazuli, redstone and coal, also the coal it has left after smelting |
| `ores` | `#c:raw_materials` and `#c:ores`: raw items it could not smelt, ore blocks |
| `pickaxes` | `#minecraft:pickaxes`: the pickaxes it held before taking a better one |
| `other` | the rest: cobblestone and whatever else it dug |

A chest comes from [`lib.get`](../reference/modules/lib/get.md): carried, from a chest within the radius, or crafted from planks or logs.

## Where it is

The job keeps its progress in `memory["ore_miner"]`: the home the claim belongs to, its first chunk and the height it mines, how many chunks of the claim it has mined out (`cleared`), where it starts in the chunk it mines (`bottom`, the bottom of the stairs or the end of the tunnel into it, and `way`, the way the stairs or tunnel went), the floors of the steps it leaves (`guard`), the waypoints it walks down to `bottom` by (`route`), and the branches of that chunk it has dug (`done`, as `"<column> <facing>"`). A bot that is stopped, or the server restarted, goes back down the same stairs and on with the branches it has not dug; run again on a claim it has mined out, it says it is finished at once. Another home, first chunk or height starts a new claim.

## In a script

```python
import jobs.ore_miner
jobs.ore_miner.run("raw_iron")
```

`walk(route)` walks to each point of `route` in turn and gives `result()`, with `at` the point it could not reach.

`run(ore="raw_iron", chunks=9, radius=32, y=None, area=None)` gives the result that ended it: `ok` with `finished` once the claim is mined out. `y` is the height to mine at, the ore's best height where `None` (8 below home where that is lower); `area` the columns to mine in place of the home chunk, as `(x0, z0, x1, z1)`, the corners included, for a smaller area or one elsewhere; the rest of the claim is then areas of its size spiralling out from it.
