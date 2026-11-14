# Cookbook

Recipes: complete scripts that combine builtins into useful bots, each with how it works and links to the reference pages it uses.

- [Recover items after death](recover-items.md): `deaths`, `last_death` and `memory` bring a lumberjack back for what it dropped.
- [Auto-farm](farm.md): harvest, till and replant a wheat field, and store the wheat; or run the bundled `jobs.farm` over any field.
- [Mining loop](mining-loop.md): a shaft dug with `lib/shaft`, its levels mined in turns with `lib/mine`, the ore brought up.
- [Keep a chest stocked](stock-chest.md): a hauler that tops a chest up from a barrel.
- [Store and sort](store.md): `jobs.store` puts away what the bot carries with `lib/storage`, each item with its kind, skipping full chests, and `sort` gathers each item into one chest.
- [Survive reloads and respawns](memory.md): a tunnel digger that keeps its progress in `memory`.
- [Fell a giant tree](giant-tree.md): the job `jobs/giant_tree`, an axe chosen by the logs it takes, borrowed tools given back.
- [Bootstrap a bot from nothing](bootstrap.md): `jobs.bootstrap` gets the starting kit of `lib/bootstrap` from trees and stone, borrowing chest tools and giving them back.
- [Get anything from nothing](get.md): `jobs.get <item> [n]` gets any item and the tools for it with `lib/get`, an iron pickaxe from a tree, stone and iron ore.
- [Strip-mine for an ore](strip-mine.md): `jobs.strip_mine` stairs down and branches for an ore, keeping a pickaxe up from the cobblestone it dug, and climbs back.
- [Explore and find a village](explore.md): `jobs.explore village` walks a spiral with `lib/explore` until it sees a village, goes home and reports the biomes and structures it saw.
- [Build a blueprint](build.md): `jobs.build <blueprint> x y z` gets what the site lacks with `lib/build`, builds it and gives back what it borrowed.
- [Settle a new base](settle.md): `jobs.settle` is one `lib/project` of kit, flatten, starter house, chest and crafting table, field, store and home, which a stopped run resumes.
- [Grow a wood farm](wood-farm.md): `jobs.wood_farm birch_sapling` builds tree cells with `lib/treefarm` on a `lib/cells` grid, stores the wood in a `lib/depot` and adds a cell whenever the chests hold one's materials, farming until it is stopped.

Each recipe's script also runs as it is in a game test (`CraftGameTest` for the chest, `CookbookGameTest` for the farm, the item recovery and the tunnel, and `BootstrapGameTest`, `GetJobGameTest`, `GiantTreeGameTest`, `StripMineGameTest`, `ExploreGameTest`, `FarmGameTest`, `BuildGameTest`, `SettleGameTest`, `WoodFarmGameTest` and `StorageGameTest` for the jobs, which are the bundled `jobs/` scripts), so a change of the API that breaks one shows. The mining loop only compiles: its shaft and levels go far deeper than a test area of 8 by 8 by 8 blocks, and `ShaftGameTest` and `MineGameTest` test `lib/shaft` and `lib/mine` that it runs on.

Two short examples:

Punch a tree, then make a wooden pickaxe:

```python
tree = find_nearest("#minecraft:logs")
if not tree:
    say("no tree near")
    stop()
goto(tree, near=2)
while count("#minecraft:logs") < 3:
    log = find_nearest("#minecraft:logs", radius=6)
    if not log or not mine(log):
        break
pick_up("#minecraft:logs")
craft("oak_planks", 9)
craft("stick", 2)
table_spot = pos().north(2)
craft("crafting_table") and place(table_spot, "crafting_table")
r = craft("wooden_pickaxe", table=table_spot)
say("pickaxe!" if r else f"no pickaxe: {r.reason}")
```

A miner with a library function (`lib.shaft`, see [Library modules](../reference/index.md#library-modules)):

```python
import lib.shaft

r = lib.shaft.dig_shaft([40, 20])
while not r["ok"]:
    say(f"shaft stopped: {r['reason']} {r['missing']}")
    wait(200)  # give it what it lacks meanwhile
    r = lib.shaft.dig_shaft([40, 20])
chest = lib.shaft.shaft()["level"][20]["chest"]
for ore in scan("#c:ores/iron", radius=8):
    mine(ore)
put(chest, "#c:raw_materials")
lib.shaft.to_surface()
```
