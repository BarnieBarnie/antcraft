# The script library

A big, stable library of default scripts that any bot script builds on, so a player writes the few lines that are theirs and leaves the rest to the library. This page is the plan: the rules every library function follows, the layers it is built in, the modules and their building blocks, and four example jobs that prove the approach. What is done is listed under [Library modules](../reference/index.md#library-modules).

## Status

The plan is done (#503 to #514). Every module below exists, with a reference page under `reference/modules/lib/`, and so do the four example jobs, each with a game test and a cookbook page: [bootstrap](../cookbook/bootstrap.md), [get](../cookbook/get.md), [giant_tree](../cookbook/giant-tree.md) and [strip_mine](../cookbook/strip-mine.md). Later issues grew it: `lib.stock.take_up_to`, `lib.borrow.keep`, charcoal where coal is deep (`wood_for_coal`), `lib.trees.KEEP`, `tier_for` at a position and the `tag_items` sense. A job is a script in the folder `jobs/`, so it runs by its dotted name: `/antcraft bot run jobs.giant_tree`, not `jobs/giant_tree`.

## Rules

The library is written the Unix way: small functions that each do one thing well, chained into bigger ones.

1. **One function, one job.** A building block does one thing and says in its docstring what it needs and what it gives. `trees.fell(base)` fells a tree; it does not fetch an axe.
2. **Preconditions are the caller's.** A block assumes what it needs (an axe held, a pickaxe of the right tier, room in the inventory) and fails with a reason when it is not there. Only the resolver (`lib.get`) satisfies preconditions, so the chain of who fetches what lives in one place and the blocks stay small.
3. **Two kinds of function, as in the API.** *Finders* (like senses) take no ticks and give a value or `None`: `find.exposed("#c:ores/iron")`. *Doers* (like actions) take ticks and give a `result()` dict: `ok`, `reason`, `at`, `missing`.
4. **Doers that obtain things ensure totals.** `get("stone_pickaxe", 1)` means "carry at least one"; called again it does nothing. Running a doer twice, after a reload, a death or a `retry`, is safe. The low-level gatherers (`trees.logs(n)`, `dig.stone(n)`) keep "n more", since they are only called through `get`.
5. **Layers only import downwards.** A module never imports one of its own layer or above, so there are no import cycles (`circular import: lib.a -> lib.b -> lib.a` fails a script).
6. **What the bot borrowed it gives back.** Anything taken from a chest goes through `lib.borrow`, which remembers it in `memory`; `borrow.give_back()` returns it at the end of a job, used or not (a worn tool comes back worn).
7. **Gaps are open.** The resolver looks raw items up in a table, `lib.get.SOURCES`; a script adds a source for anything the library cannot get yet (`SOURCES["minecraft:sand"] = Source(my_sand, tool="shovel")`) without changing the library.
8. **Each block has a game test or a unit test,** and each module a reference page under `reference/modules/lib/`.

## Layers

```text
 jobs/        jobs/bootstrap.py  jobs/get.py  jobs/giant_tree.py  jobs/strip_mine.py  jobs/explore.py  jobs/build.py  jobs/wood_farm.py   run with bot run jobs.<job> [args]
 kits         lib/bootstrap  lib/gather  lib/project  lib/build  lib/depot  lib/treefarm         a set of things a job starts with; make and gather over get; resumable steps; a blueprint end to end; a storage row that grows; tree cells
 resolver     lib/get          get(item, n), ensure_tool(kind, uses), SOURCES           chests, crafting, gathering, recursively
 skills       lib/trees  lib/dig  lib/craft  lib/stock  lib/tools  lib/inv  lib/shaft  lib/mine  lib/explore  lib/farm  lib/storage  lib/terrain     one skill each, preconditions assumed
 primitives   lib/result  lib/find  lib/borrow  lib/cost  lib/area  lib/cells  lib/retry  lib/home  no ticks, or a single action
 builtins     goto, mine, place, craft, scan, ore_vein, needs, ...                                  the API (Java)
```

## Modules

New modules are marked *new*; the others exist and grow.

### Primitives

- `lib/result` *new*: `result(reason=None, at=None, missing=None)`, moved out of `lib/shaft` (which keeps importing it, so old scripts work).
- `lib/find` *new*, finders over `scan` and `find_nearest`: `nearest(what, radius, where=None)` with a filter, `exposed(what, radius)` (a block with an air neighbour the bot can stand by, nearest at or above its feet first, as `lib.gather.exposed_stone` does now).
- `lib/borrow` *new*: `borrow(chest, item, n)` takes and records it in `memory["borrowed"]`; `borrowed()` lists what is out; `keep(item, n)` strikes what a job keeps off it; `give_back()` puts each item back in its chest (where the chest is gone or full, into the nearest chest that has room, else kept and named in `missing`).
- `lib/cost` *new*, the numbers the heuristics need: `WALK` ticks a block, `BREAK[material][tier]` ticks to break a block, `USES[tier]` a tool's uses (59, 131, 190 for copper, 250, 1561, 32 for gold, 2031 for netherite), `SMELT` ticks an item; `walk(a, b)`, `breaking(n, material, tier)`. ponytail: constants, not the game's break-time formula; a `break_ticks(pos, item)` sense if estimates prove too rough.
- `lib/cells` (#603): a self-expanding farm's grid, kept in `memory["cells"][name]`: `layout(name, origin, size, row=4, gap=1)`, `cell(name, i)` the `(low, high)` box of cell `i` (row-major, `gap` blocks of path between cells) for `lib/area`, `built`, `add`, `next` the first cell not built, `count`. Generic: the wood farm (#605, #606) and later farms keep their own logic.
- Existing: `lib/area`, `lib/retry`, `lib/home`.

### Skills

- `lib/tools` *new*: `uses_left(stack)` (needs [durability on `Stack`](#engine-gaps)), `best(kind)` the best carried tool, `enough(kind, uses)` whether the carried ones last `uses` more blocks, `tier_for(block)` the lowest pickaxe tier that drops it (iron for diamond ore), `hold(kind)` (moved from `lib/craft`).
- `lib/stock` *new*, the chests a bot may take from: `chests(radius=32)` (its colony's core, the chests in `memory["chests"]`, and those within `radius`), `where(item)` the chests holding it, nearest first, `fetch(item, n)` takes through `borrow` until the bot carries `n`. Replaces `lib.craft.fetch` and the chest half of `lib.inv.take_all`.
- `lib/craft`: `plan`, `craft_all`, `craft_here`, `smelt_here` stay; `obtain` and `ensure_tool` stay as they are, taking from chests only (borrowed): a skill cannot import the resolver above it, so a script that wants gathering and a tier by cost calls `lib.get`.
- `lib/trees`: adds `tree_blocks(base)`, the logs of one tree, found by a flood from its base through logs of its kind (diagonal steps too, for acacia and jungle branches, a cap of 512 blocks); `is_giant(base)` for a 2x2 trunk; `stands(blocks)`, the fewest standing spots within reach of every log, greedily, preferring spots on the tree's own logs so the bot places few blocks; `fell_whole(base)`, which works the stands from the top down so each stand is on logs not yet mined, and takes its scaffold back. `logs(n)` uses `fell_whole` for a tree it needs all of.
- `lib/dig` *new*, getting things out of the ground: `stone(n)` (from `lib/gather`), `ore(item, n)` (an exposed ore vein within radius by `find.exposed` and `ore_vein`, else `branch` at `best_y(item)`), `best_y(item)` (iron 16, coal 96, copper 48, gold -16, redstone and diamond -59, lapis 0), `stairs_to(y)` and `stairs_up()`, a 1-wide staircase that never digs straight down (`stairs_up` digs one up to the surface where none is remembered), `branch(length, every=8)` a 1x2 tunnel with a torch every 8 blocks, mining each ore vein it uncovers. `lib/shaft` and `lib/mine` stay for the laddered colony mine.
- `lib/explore` (#568): `spiral(centre, step, rings)` the waypoints of a square spiral, `look()` records `biome` and `structures` in `memory["seen"]` (one position per biome per chunk, one per structure), `explore(rings, step, until)` walks the spiral looking until `until(seen)`, `seen(what)` and `report()`.
- `lib/farm` (#570): `CROPS`, `ripe(pos)`, `field(low, high)` the soil of a box, `till(spots)`, `plant(spots, seed)`, `harvest(spots)` (ripe only, replanted) and `tend(low, high, seed)`; `colony.farmer` is built on it.
- `lib/storage` (#572): `KEEP_KINDS` (tools, weapons, torches, food), `deposit(keep)` gives back what is borrowed and puts the rest into the chests of `lib/stock`, each item first where it already is, and `sort(chests)` gathers each item into the chest that holds most of it.
- `lib/terrain` (#573): `needs(low, high, y)` the fill and digging it takes, `flatten(low, high, y, fill)` mines above `y` and fills the holes at `y` (air, water or lava, sources first) down to solid ground, over `lib/area`.
- Existing: `lib/inv`, `lib/shaft`, `lib/mine`.
- `lib/treefarm` (#605), a kit over `lib/terrain`, `lib/area` and `lib/trees`: `KINDS` a spec per sapling from the game's tree features (cell size, 2x2, the air the tree needs over its floor, a slab roof for oak), `materials(kind)`, `build_cell`, `grown`, `harvest` (`fell_whole` with replant) on a `lib/cells` cell; the wood farm (#606) is built on it.

### The resolver: `lib/get`

`get(item, n=1, radius=32)` makes the bot carry at least `n` of `item`, by the cheapest of:

1. what it carries;
2. chests, through `stock.fetch` (borrowed);
3. crafting: `craft.plan(item, n)` lists the raw items; `get` takes what the plan crafts on the way (planks, sticks, ingots) from the chests first (#560), then gets each raw item still short, then `craft.craft_here`;
4. gathering a raw item: `SOURCES[item]`, a `Source(do, tool=None, tier=None, estimate=None, ore=None, near=None, y=None)`: `ore` the block it digs and `y` the height it digs down to for it, so its cost adds `deep(y)`, the trip down to `y`, a branch and back, where none of `ore` is exposed (`DEEP` without `y`, #564); `near` what `do` gets it from, where none within the radius means there is nothing to get. `get` first makes the bot hold the tool (`ensure_tool(tool, uses, tier)`), then calls `do(n, radius)`.

`ensure_tool(kind, uses=1, tier=None)` makes the bot hold a tool of `kind` that lasts `uses` blocks and is at least `tier`: a carried one or one in a chest (one of no listed tier, as a modded one, only where no tier is asked); one from a chest whose walk and breaking cost no more than making the lowest tier (borrowed); a failure at once where no log is in reach to make one from; else the cheaper of a far chest's and the tier whose cost to make plus `cost.breaking(uses, tier)` is lowest, counting the tools of a cheaper tier it would wear out. So felling three oaks takes a stone axe, a forest of jungle trees an iron one, and a chest's iron axe beats crafting either. The choice is a finder, `choose(kind, uses, tier)` (the cheapest of `options(...)`), so it is tested without ticks. A source's `tier="hand"` means the bare hand drops the item too (logs, dirt): the tool is then only made where it beats the hand, which also lets an empty bot bootstrap (logs by hand, a wooden pickaxe, cobblestone, stone tools). `get` keeps the items it is getting in `GETTING`: a tier whose recipe needs one of them is not made for it, and a get that waits on itself gives `cycle`.

`SOURCES` starts with: any log (`trees.logs`, axe), cobblestone (`dig.stone`, pickaxe), coal, raw iron, raw copper, raw gold, redstone, lapis, diamond (`dig.ore`, pickaxe of `tools.tier_for`), dirt and sand (`dig.exposed` and `mine`, shovel). `lib/gather` keeps `make` and `gather` as wrappers of `get`.

### Kits: `lib/bootstrap`

`kit(items=KIT)` gets each of `KIT`, a list of `(item, n)` in order: `("stone_pickaxe", 1)`, `("stone_axe", 1)`, `("stone_shovel", 1)`, `("stone_sword", 1)`, `("crafting_table", 1)`, `("furnace", 1)`, `("torch", 16)`. A job calls `lib.bootstrap.kit()` first, or `kit(MINER)` with a list of its own, and works with what it got.

### Kits: `lib/project`

`run(name, steps)` does a list of `Step(name, do, args=(), tries=1, optional=False)` in order and keeps the names of those done in `memory["projects"][name]`, so a project (settle a base, build a house, mine out a level) run again after a reload, a death or a stop goes on at the step it was on (#569). Each step goes through `lib.retry` up to `tries`; an optional step that fails is noted in `failed` and passed, a required one stops the project with its reason. It ends with `borrow.give_back()`, also on a failure, and gives `result()` with `missing` the list of the steps not done. `status(name)` and `reset(name)` read and forget it.

### Kits: `lib/build`

`materials(id, at, turn=0, mirror=False)` is what a blueprint's site still lacks (`blueprint(...)["missing"]`, #554); `build_here(id, at, turn=0, mirror=False, radius=32)` gets each of it with `get`, then `build`s what it has (where a `get` failed, as far as it can, then fails with that reason and what the bot lacks in `missing`, #585), again while each round leaves less missing, and gives back what it borrowed, also on a failure (#571). `colony.builder` takes from the core first and posts haul jobs for what `get` fails on, so it uses `materials` and builds itself.

## Farms

A farm is a job that runs until a player stops it, grows itself as it earns its materials, and picks up where it was after a stop, a reload or a death. `jobs/wood_farm.py` (#606, [cookbook](../cookbook/wood-farm.md)) is the first; a later farmer, such as a cobblestone generator or a sugar cane farm, follows the same pattern:

1. **Cells** (`lib/cells`): the farm is a grid of identical cells laid out once from an origin; it builds `next` and marks it `add`ed, so what is built survives a rerun. The farm's own kit builds, checks and harvests one cell (`lib/treefarm` for trees).
2. **Depot** (`lib/depot`): a row of chests by the farm, a category each, that adds a chest as one fills, so the farm never stops on a full chest. The bot keeps what it needs for its cells and stores the rest.
3. **Project** (`lib/project`): the setup (kit, first materials, first cell) as steps, and the loop as its last step, which never returns. A rerun skips the steps done and goes straight back to the loop; as `give_back` never runs, the farm keeps what it took (it strikes it off `lib/borrow` each round).
4. **Loop**: each round harvests what is ready, stores it, puts back what a cell lacks (a sapling, a torch), builds the next cell where the farm is below its most and the chests hold a cell's materials, and `wait`s where nothing was ready. Its most is the size at which the bot is always busy: how long a cell takes to be ready again over how long the bot takes for one, from the game's numbers (random ticks for growth) and a measured harvest time.
5. **Imported, it runs nothing**: the job reads `ARGS` in a `try` (an imported module has none) and exposes `run(...)`, so another job, such as a forester that picks the kind (#607), starts the farm with its own arguments.

## Engine gaps

What the library needs from the API:

- **Durability on `Stack`** (Java): `damage` and `max_damage` (both 0 for an item that does not wear), so `tools.uses_left` and `ensure_tool` can count.
- Nothing else up front. A block that turns out too slow in a script (the tree flood, `stands`) moves to a sense, as `ore_vein` did, once a benchmark shows it.

## Example jobs

Eleven jobs in `jobs/`, each a few lines over the library, each with a game test. A job runs by its dotted name, such as `/antcraft bot run jobs.get iron_pickaxe`. Each that borrows ends with `borrow.give_back()`, also when it fails.

1. **`jobs/bootstrap.py`**: an empty-handed bot gets `lib.bootstrap.KIT` from what is around: logs, a crafting table, a wooden pickaxe, cobblestone, the stone tools, a furnace, charcoal and torches; tools in a nearby chest are borrowed instead of made. Test: an empty bot by trees and stone; again with an iron pickaxe in a chest, which it uses and gives back.
2. **`jobs/get.py <item> [n]`**: `get(ARGS[0], n)`, the resolver alone. Test: `iron_pickaxe` from nothing, with trees, stone and iron ore in the area.
3. **`jobs/giant_tree.py [x y z]`**: finds the nearest giant tree (2x2 jungle, spruce or dark oak) to a position, `tree_blocks`, `ensure_tool("axe", len(logs))`, `fell_whole`, replants four saplings. Test: a short 2x2 tree built in the area; all logs taken, no scaffold left, fewer than a set number of blocks placed.
4. **`jobs/strip_mine.py <ore> [n] [y] [radius] [facing]`**: `kit(MINER)`, `stairs_to(best_y(ore))`, `branch` until it carries `n`, keeping a pickaxe through `ensure_tool` (a new stone one from the cobblestone it dug), then `stairs_up()` and home. Test: seeded ore at a set depth.
5. **`jobs/explore.py [what] [rings] [step]`**: `lib.explore.explore` until `seen(what)`, then home and `report()`. Tests: a village put in the area, found by `explore` with 2-block steps; the job walking a whole spiral of them for what is not there.
6. **`jobs/farm.py <x1> <y1> <z1> <x2> <y2> <z2> [crop] [rounds]`**: `ensure_tool("hoe")`, the seeds with `get`, `lib.farm.tend`, `give_back()` (#583: before the harvest is stored, as carrots and potatoes are their own seed), the harvest into the nearest chest; `rounds` rounds (0: until one fails), waiting for the crops to ripen between them. Test: a 3 by 3 field with a hoe and seeds in a chest.
7. **`jobs/store.py [radius]`**: `lib.storage.deposit`, then home. Test: dirt carried, a chest in the area.
8. **`jobs/build.py <blueprint> x y z [turn] [radius]`**: `lib.build.build_here`, then says whether it built it. Tests: a one-cobblestone blueprint built from stone with a borrowed pickaxe, given back; the cobble step with no wood about built as far as its oak stairs, failing with them in `missing`; `no_blueprint`; `no_progress`.
9. **`jobs/flatten.py <x1> <z1> <x2> <z2> [y] [fill]`**: `ensure_tool` for each kind `lib.terrain.needs` lists, `lib.terrain.flatten` with `fill` (dirt by default), `get` the fill it fell short of and flatten again, `give_back()`. Test: a 4 by 4 plot with a bump and a hole, levelled at its median height; a given fill; the dirt it lacks from a chest.
10. **`jobs/settle.py [x y z [radius]]`**: one `lib.project.run("settle", ...)`: `kit` (stone tools, a crafting table, a chest, dirt), `lib.terrain.flatten`, `lib.build.build_here` of `antcraft:starter_house`, `place` the chest and crafting table, `lib.farm.tend` a 3 by 3 field (optional), `lib.storage.deposit` (optional), `set_home` inside. Test (wide area): stopped as it starts the house and run again, the kit and flatten are not done again; the house stands, the field is planted, the borrowed tools are back ([cookbook](../cookbook/settle.md)).
11. **`jobs/wood_farm.py <sapling> [x y z [cells [radius]]]`**: one `lib.project.run` of `kit` (a stone axe, dirt), two saplings a spot by `get`, the first `lib.treefarm` cell on a `lib.cells` grid, then a loop that never ends (see [Farms](#farms)). Tests (24 by 16 by 24 area): the first cell built, its birch grown by the test, felled, replanted and the logs stored in the depot; a second cell from the materials in a chest; stopped and run again, the same farm goes on; `most()` ([cookbook](../cookbook/wood-farm.md)).

A test area is 8x8x8 blocks: tests build small trees and seed ore close, and set `best_y` through an argument rather than dig to y=16.
