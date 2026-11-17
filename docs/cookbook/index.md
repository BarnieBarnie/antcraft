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
- [Grow a wood farm](wood-farm.md): `jobs.wood_farm birch_sapling` builds tree cells with `lib/treefarm` on a `lib/cells` grid, stores the wood in a `lib/depot` and adds a cell whenever the chests hold one's materials, farming until it is stopped; `jobs.forester` picks the tree kind from the saplings in nearby chests and the trees around.
- [Grow a sugarcane farm](cane-farm.md): `jobs.cane_farm` builds cells of water with cane either side on `lib/cellfarm`, cuts the cane to its bottom block, stores it and plants a new cell from it.
- [Grow a pumpkin or melon farm](stem-farm.md): `jobs.stem_farm pumpkin` builds cells of stems round a water source with `lib/cellfarm`, mines the fruit and stores it, and crafts the seeds for each new cell from the harvest.
- [Grow a crop farm](crop-farm.md): `jobs.crop_farm carrot` lays out 9x9 cells of wheat, carrots, potatoes or beetroot round a water source on `lib/cellfarm`, stores the harvest and adds a cell whenever it has a cell's worth of seed.
- [Grow a bamboo farm](bamboo-farm.md): `jobs.bamboo_farm` plants cells of three bamboo on `lib/cellfarm`, cuts each stalk to its bottom block, sweeps up and stores the drops and plants a new cell from them.
- [Grow a cocoa farm](cocoa-farm.md): `jobs.cocoa_farm` builds cells of two jungle logs with cocoa pods on their sides on `lib/cellfarm`, breaks the ripe pods, places a bean back and stores the rest, and adds a cell from stored beans and logs.
- [Grow a cactus farm](cactus-farm.md): `jobs.cactus_farm` builds cells of cactus on sand in a checkerboard on `lib/cellfarm`, cuts each cactus to its bottom block, stores it and plants a new cell from it, its paths keeping off the blocks beside a cactus.
- [Grow a nether wart farm](wart-farm.md): `jobs.wart_farm` plants cells of three wart on soul sand on `lib/cellfarm`, harvests and replants the ripe wart, stores the rest and builds a new cell from it and stored soul sand.
- [Grow a mushroom farm](mushroom-farm.md): `jobs.mushroom_farm` plants a brown and a red mushroom on cells of three podzol on `lib/cellfarm`, harvests what they spread onto, stores it and builds a new cell from it and stored podzol.
- [Grow a sweet berry farm](berry-farm.md): `jobs.berry_farm` plants cells of three sweet berry bushes on dirt on `lib/cellfarm`, picks the ripe bushes without breaking them, stores the berries and plants a new cell from them, its paths never entering a bush.
- [Farm kelp](kelp-farm.md): `jobs.kelp_farm` plants kelp on the floor of a pool two deep or more, cuts it to its bottom block from the rim or, where that is out of reach, from the surface of the water, and stores it, raw or, with `dry`, smelted to dried kelp and crafted into dried kelp blocks.
- [Keep a ranch](ranch.md): `jobs.ranch` lures cows, sheep, pigs and chickens into a fenced pen and breeds them with their food, kills the adults over a cap but never a baby, shears the sheep, milks the cows and stores the meat, leather, wool, eggs and milk.
- [Guard home](guard.md): `jobs.guard` fights the hostile mobs near home with `attack`, and when hurt walks home, eats with `lib/food` and heals before it fights again.

Each recipe's script also runs as it is in a game test (`CraftGameTest` for the chest, `CookbookGameTest` for the farm, the item recovery and the tunnel, and `BootstrapGameTest`, `GetJobGameTest`, `GiantTreeGameTest`, `StripMineGameTest`, `ExploreGameTest`, `FarmGameTest`, `BuildGameTest`, `SettleGameTest`, `WoodFarmGameTest`, `CaneFarmGameTest`, `StemFarmGameTest`, `CropFarmGameTest`, `BambooFarmGameTest`, `CocoaFarmGameTest`, `CactusFarmGameTest`, `WartFarmGameTest`, `MushroomFarmGameTest`, `BerryFarmGameTest`, `KelpFarmGameTest`, `RanchGameTest`, `StorageGameTest` and `GuardGameTest` for the jobs, which are the bundled `jobs/` scripts), so a change of the API that breaks one shows. The mining loop only compiles: its shaft and levels go far deeper than a test area of 8 by 8 by 8 blocks, and `ShaftGameTest` and `MineGameTest` test `lib/shaft` and `lib/mine` that it runs on.

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
