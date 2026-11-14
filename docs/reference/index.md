# API reference

Every builtin a script can call, grouped by what it is for. The API is available as builtins in every script (no import needed); names are `snake_case`. Positions are [`Pos`](types/pos.md) values; anything that takes a position also takes a tuple `(x, y, z)`.

Senses are done at once and return a value. Actions take ticks, pause the script until they are done and return a [`Result`](types/result.md).

## Types

- [`Pos`](types/pos.md): a block position, with its dimension; `Pos(x, y, z)` makes one.
- [`Block`](types/block.md): a block as `block(pos)` reads it.
- [`Stack`](types/stack.md): an item and how many.
- [`Entity`](types/entity.md): a mob, player or dropped item.
- [`Result` and reason codes](types/result.md): what an action returns, and why it failed.
- [Selectors](types/selectors.md): the strings that pick items and blocks.
- [`memory` and `colony_memory`](types/memory.md): the dicts a bot and its colony keep.
- [`str`](types/str.md), [`list`](types/list.md) and [`dict`](types/dict.md) methods: `split`, `join`, `append`, `pop`, `get`, `items`, ...

## Movement

| Builtin | Signature | Does |
|---|---|---|
| [`goto`](movement/goto.md) | `goto(pos, near=0, keep=None)` | action: walk to within `near` blocks, digging, pillaring and bridging as needed, never with what `keep` selects |
| [`travel`](movement/travel.md) | `travel(portal)` | action: go through the nether portal at `portal` to the other dimension |
| [`reachable`](movement/reachable.md) | `reachable(pos, near=0)` | whether the bot could walk to within `near` blocks of `pos`; plans without moving (may pause) |
| [`pos`](movement/pos.md) | `pos()` | the bot's position, with its dimension |
| [`Pos`](types/pos.md) | `Pos(x, y, z, dimension=None)`, `Pos(position)` | a position made from ints, a tuple or another `Pos` |
| [`home`](movement/home.md) | `home()` | the bot's home, with its dimension |
| [`set_home`](movement/set_home.md) | `set_home(pos)` | sets the bot's home |
| [`home_dimension`](movement/home_dimension.md) | `home_dimension()` | the id of the dimension of the bot's home |
| [`dimension`](movement/dimension.md) | `dimension()` | the id of the dimension the bot is in, such as `"minecraft:overworld"` |
| [`portals`](movement/portals.md) | `portals()` | both ends of the nether portals the bot went through, newest first |
| [`placed_scaffold`](movement/placed_scaffold.md) | `placed_scaffold()` | where the script's walks placed scaffold blocks since it last asked, and forgets them |

## The world around

| Builtin | Signature | Does |
|---|---|---|
| [`block`](world/block.md) | `block(pos)` | the [`Block`](types/block.md) at `pos`, or `None` where it is not loaded |
| [`block_tags`](world/block_tags.md) | `block_tags(block)` | the tags of a block id, such as `"#minecraft:needs_iron_tool"` |
| [`surface`](world/surface.md) | `surface(pos)` | the open spot on top of `pos`'s column, under the sky, or `None` where it is not loaded or in the Nether |
| [`biome`](world/biome.md) | `biome(pos=None)` | the id of the biome at `pos` (the bot's feet), or `None` where it is not loaded |
| [`biome_tags`](world/biome_tags.md) | `biome_tags(biome)` | the tags of a biome id, such as `"#minecraft:is_forest"` |
| [`structures`](world/structures.md) | `structures(radius=64)` | the structures (villages, mineshafts, ...) whose box is near, nearest first, in loaded chunks |
| [`find_nearest`](world/find_nearest.md) | `find_nearest(what, radius=32, around=None)` | the nearest matching block's position (to the bot or `around`), or `None` (may pause) |
| [`scan`](world/scan.md) | `scan(what, radius=16, limit=64, around=None)` | the positions of matching blocks, nearest first (to the bot or `around`) (may pause) |
| [`entities`](world/entities.md) | `entities(what=None, radius=16)` | the [`Entity`](types/entity.md) values around, nearest first |
| [`items_near`](world/items_near.md) | `items_near(item=None, radius=16)` | the dropped items around, nearest first |
| [`time`](world/time.md) | `time()` | the time of day, 0 to 23999 |
| [`is_day`](world/is_day.md) | `is_day()` | whether it is day |

## Inventory

| Builtin | Signature | Does |
|---|---|---|
| [`inventory`](inventory/inventory.md) | `inventory()` | the carried [`Stack`](types/stack.md) values, the held one first |
| [`held`](inventory/held.md) | `held()` | the `Stack` in the bot's hand, or `None` |
| [`count`](inventory/count.md) | `count(item)` | how many matching items the bot carries |
| [`has`](inventory/has.md) | `has(item, n=1)` | whether the bot carries at least `n` matching items |
| [`free_slots`](inventory/free_slots.md) | `free_slots()` | how many inventory slots are empty |
| [`matches`](inventory/matches.md) | `matches(item, selector)` | whether an item id or a `Stack` is one the [selector](types/selectors.md) selects |
| [`tag_items`](inventory/tag_items.md) | `tag_items(tag)` | the item ids of an item tag, such as `"#antcraft:scaffold_blocks"` |
| [`equip`](inventory/equip.md) | `equip(item)` | hold a carried item |
| [`drop`](inventory/drop.md) | `drop(item, n=None)` | toss up to `n` matching items |
| [`pick_up`](inventory/pick_up.md) | `pick_up(what=None, radius=8)` | action: walk to dropped items and take them |

## Working blocks

| Builtin | Signature | Does |
|---|---|---|
| [`mine`](blocks/mine.md) | `mine(pos)` | action: break the block with the best tool carried |
| [`place`](blocks/place.md) | `place(pos, item, facing=None, half=None)` | action: place a block |
| [`use`](blocks/use.md) | `use(pos, item=None)` | action: use the held item, or the block itself, on a block as a right click does |

## Containers and crafting

| Builtin | Signature | Does |
|---|---|---|
| [`container`](crafting/container.md) | `container(pos)` | the `Stack` values in a chest, barrel or furnace, or `None` |
| [`room`](crafting/room.md) | `room(pos, item)` | how many of an item fit in a container, or `None` |
| [`put`](crafting/put.md) | `put(container, item, n=None)` | action: put items into a container |
| [`take`](crafting/take.md) | `take(container, item, n=None)` | action: take items out of a container |
| [`craft`](crafting/craft.md) | `craft(item, n=1, table=None)` | action: craft from carried items |
| [`smelt`](crafting/smelt.md) | `smelt(item, n=1, furnace=None, fuel=None)` | action: smelt carried items in a furnace, blast furnace or smoker |
| [`needs`](crafting/needs.md) | `needs(item, n=1, also=None)` | the raw items still to gather and the craft and smelt steps to make an item |
| [`cooks_in`](crafting/cooks_in.md) | `cooks_in(item)` | which of the furnace, smoker and blast furnace cook an item |

## Building

| Builtin | Signature | Does |
|---|---|---|
| [`build`](building/build.md) | `build(blueprint, at, turn=0, mirror=False, job=None)` | action: build a blueprint from carried blocks |
| [`build_placement`](building/build_placement.md) | `build_placement(name, job=None)` | action: build a Litematica placement the bot's owner sent |
| [`blueprint`](building/blueprint.md) | `blueprint(id, at=None, turn=0, mirror=False)` | a blueprint's size, role, materials, spots and, given `at`, what is still missing there, as plain data |

## Mining

| Builtin | Signature | Does |
|---|---|---|
| [`mineshaft`](mining/mineshaft.md) | `mineshaft(levels, near=None, branches=4, length=16)` | where a ladder shaft can go, and its plan (may pause) |
| [`mineshaft_at`](mining/mineshaft_at.md) | `mineshaft_at(top, levels, round=0, branches=4, length=16)` | the plan of a shaft already started at `top` |
| [`ore_vein`](mining/ore_vein.md) | `ore_vein(pos)` | the blocks of the ore vein at `pos` |

## Fighting

| Builtin | Signature | Does |
|---|---|---|
| [`attack`](combat/attack.md) | `attack(entity)` | action: walk to a hostile mob or animal and strike it until it dies |

## Colony and jobs

| Builtin | Signature | Does |
|---|---|---|
| [`colony`](colony/colony.md) | `colony()` | the bot's colony as a dict, or `None` |
| [`post_job`](colony/post_job.md) | `post_job(kind, data=None)` | post a job on the colony's job board |
| [`take_job`](colony/take_job.md) | `take_job(kind=None)` | take a job off the job board |
| [`jobs`](colony/jobs.md) | `jobs(kind=None)` | the jobs on the job board |
| [`finish_job`](colony/finish_job.md) | `finish_job(id)` | mark a taken job done |
| [`drop_job`](colony/drop_job.md) | `drop_job(id)` | put a taken job back on the board |

## The bot and its output

| Builtin | Signature | Does |
|---|---|---|
| [`deaths`](bot/deaths.md) | `deaths()` | how often the bot has died |
| [`last_death`](bot/last_death.md) | `last_death()` | where the bot last died, or `None` |
| [`health`](bot/health.md) | `health()` | the bot's health now, `0.0` to `20.0` |
| [`max_health`](bot/max_health.md) | `max_health()` | the most health the bot can have, `20.0` |
| [`say`](bot/say.md) | `say(text)` | show text above the bot and in its owner's chat |
| [`print`](bot/print.md) | `print(*values, sep=" ")` | write a line to the bot's log, always shown |
| [`debug`](bot/debug.md) | `debug(*values, sep=" ")` | write a line to the bot's log at debug level |
| [`info`](bot/info.md) | `info(*values, sep=" ")` | write a line to the bot's log at info level |
| [`warn`](bot/warn.md) | `warn(*values, sep=" ")` | write a line to the bot's log at warn level |
| [`error`](bot/error.md) | `error(*values, sep=" ")` | write a line to the bot's log at error level |
| [`wait`](bot/wait.md) | `wait(ticks)` | action: do nothing for `ticks` ticks |
| [`stop`](bot/stop.md) | `stop()` | end the script |

## Python builtins

| Builtin | Signature | Does |
|---|---|---|
| [`abs`](python/abs.md) | `abs(x)` | the absolute value |
| [`all`](python/all.md) | `all(iterable)` | whether every element is truthy |
| [`any`](python/any.md) | `any(iterable)` | whether any element is truthy |
| [`bool`](python/bool.md) | `bool(x=False)` | `x` as a bool |
| [`dict`](python/dict.md) | `dict(**names)`, `dict(source, **names)` | a new dict |
| [`enumerate`](python/enumerate.md) | `enumerate(iterable, start=0)` | pairs of index and element |
| [`float`](python/float.md) | `float(x=0.0)` | `x` as a float |
| [`int`](python/int.md) | `int(x=0)`, `int(x, base)` | `x` as an int |
| [`isinstance`](python/isinstance.md) | `isinstance(obj, class_or_tuple)` | whether `obj` is of the type, or one of the types |
| [`len`](python/len.md) | `len(obj)` | the number of elements |
| [`list`](python/list.md) | `list(iterable=())` | a new list |
| [`max`](python/max.md) | `max(iterable, key=None, default=None)`, `max(a, b, *more, key=None)` | the largest element |
| [`min`](python/min.md) | `min(iterable, key=None, default=None)`, `min(a, b, *more, key=None)` | the smallest element |
| [`range`](python/range.md) | `range(stop)`, `range(start, stop, step=1)` | a range of ints |
| [`reversed`](python/reversed.md) | `reversed(sequence)` | the elements back to front |
| [`round`](python/round.md) | `round(number, ndigits=None)` | `number` rounded |
| [`sorted`](python/sorted.md) | `sorted(iterable, key=None, reverse=False)` | a sorted list |
| [`str`](python/str.md) | `str(object="")` | `object` as a string |
| [`sum`](python/sum.md) | `sum(iterable, start=0)` | the sum |
| [`tuple`](python/tuple.md) | `tuple(iterable=())` | a new tuple |
| [`zip`](python/zip.md) | `zip(*iterables)` | tuples of the elements side by side |

## Library modules

The mod's default library, scripts that any script can import (see [Modules & import](../language/modules.md)):

- [`lib/home`](modules/lib/home.md): `go_home()`, `at_home()`, `to_home_dimension()`, `portal_out()`.
- [`lib/shaft`](modules/lib/shaft.md): `dig_shaft(levels)`, `to_level(level)`, `to_surface()`, `shaft(levels=None)`.
- [`lib/mine`](modules/lib/mine.md): `mine_level(level, rounds=1)`, `site(r=0)`.
- [`lib/craft`](modules/lib/craft.md): `craft_all(item, n=1, table=None, furnace=None)`, `craft_here(item, n=1)`, `smelt_here(item, n=1, fuel=None)`, `plan(item, n=1)`.
- [`lib/retry`](modules/lib/retry.md): `retry(action, args, tries=5, again=None, delay=4, kwargs=None)`.
- [`lib/inv`](modules/lib/inv.md): `store_all(chest, keep=None)`.
- [`lib/trees`](modules/lib/trees.md): `next_tree(radius=32, where=None, remnants=False, around=None)`, `is_tree(base)`, `fell(base, replant=True)`, `logs(n, radius=32, kind=LOGS)`, `low_log(radius=32, skip=None)`, `tree_blocks(base)`, `is_giant(base)`, `stands(blocks)`, `fell_whole(base, replant=True)`, `take_back()`, `LOGS`, `SAPLINGS`, `SCAFFOLD`, `KEEP`.
- [`lib/area`](modules/lib/area.md): `box(a, b)`, `clear(a, b)`, `fill(a, b, item)`, `bounds(positions, margin=0)`, `inside(p, low, high)`, `centre(low, high)`.
- [`lib/cells`](modules/lib/cells.md): `layout(name, origin, size, row=4, gap=1)`, `cell(name, i)`, `built(name)`, `add(name, i)`, `next(name)`, `count(name)`, a grid of farm cells that grows one cell at a time, kept in `memory["cells"]`.
- [`lib/result`](modules/lib/result.md): `result(reason=None, at=None, missing=None)`, the dict every doer gives.
- [`lib/cost`](modules/lib/cost.md): `WALK`, `BREAK`, `USES`, `SMELT`, `walk(a, b)`, `breaking(n, material, tier)`.
- [`lib/tools`](modules/lib/tools.md): `uses_left(stack)`, `best(kind)`, `enough(kind, uses)`, `tier_for(what)`, `hold(kind)`.
- [`lib/borrow`](modules/lib/borrow.md): `borrow(chest, item, n=1)`, `borrowed()`, `give_back(radius=16)`, `keep(item, n=None)`.
- [`lib/find`](modules/lib/find.md): `nearest(what, radius=32, where=None)`, `exposed(what, radius=32, skip=None)`, `is_open(p)`, `is_air(p)`.
- [`lib/dig`](modules/lib/dig.md): `stone(n, radius=32)`, `ore(item, n, radius=32, y=None, facing="north", length=32)`, `best_y(item)`, `stairs_to(y, facing="north")`, `stairs()`, `stairs_up(facing="north")`, `branch(length, every=8, facing="north")`.
- [`lib/stock`](modules/lib/stock.md): `chests(radius=32)`, `where(item, radius=32)`, `fetch(item, n, radius=32)`, `take_up_to(item, n, radius=32, chests=None)`, `fetch_all(more, chests)`.
- [`lib/get`](modules/lib/get.md): `get(item, n=1, radius=32)`, `ensure_tool(kind, uses=1, tier=None, radius=32)`, `choose(kind, uses=1, tier=None, radius=32)`, `options(...)`, `SOURCES`, `Source(do, tool=None, tier=None, estimate=None, ore=None, near=None, y=None)`, `DEEP`, `wood_for_coal(k, radius)`, `key(item)`, the resolver.
- [`lib/bootstrap`](modules/lib/bootstrap.md): `KIT`, `kit(items=KIT, radius=32)`, the things a job starts with.
- [`lib/project`](modules/lib/project.md): `Step(name, do, args=(), tries=1, optional=False)`, `run(name, steps)`, `status(name)`, `reset(name)`, a list of steps that resumes where it stopped.
- [`lib/farm`](modules/lib/farm.md): `CROPS`, `ripe(pos)`, `field(low, high)`, `till(spots)`, `plant(spots, seed=None)`, `harvest(spots)`, `tend(low, high, seed=None)`, a field harvested, tilled and replanted.
- [`lib/storage`](modules/lib/storage.md): `KEEP_KINDS`, `deposit(keep=None, radius=32)`, `sort(chests)`, putting away loot and sorting chests.
- [`lib/terrain`](modules/lib/terrain.md): `needs(low, high, y)`, `flatten(low, high, y, fill="minecraft:dirt")`, a plot levelled at `y` before building on it; the bundled `jobs.flatten`.
- [`lib/explore`](modules/lib/explore.md): `spiral(centre, step=32, rings=4)`, `look(radius=64, around=16)`, `explore(rings=4, step=32, until=None)`, `seen(what)`, `report()`, the biomes and structures a bot has seen, kept in `memory["seen"]`.
- [`lib/build`](modules/lib/build.md): `materials(id, at, turn=0, mirror=False)`, `build_here(id, at, turn=0, mirror=False, radius=32)`, a blueprint built end to end: what its site lacks got, built, the borrowed given back.
- [`colony/common`](modules/colony/common.md): `core()`, `in_colony(p)`, `store(item)`, shared by the [default colony scripts](../features/colony-scripts.md).

Kept for old scripts, each now a wrapper of the library function after it; new scripts call that one:

- [`lib.craft.obtain`](modules/lib/craft.md#kept-for-old-scripts): `lib.get.get`; [`lib.craft.ensure_tool`](modules/lib/craft.md#kept-for-old-scripts): `lib.get.ensure_tool`; `lib.craft.fetch`: `lib.stock.fetch_all`.
- [`lib.inv.take_all`](modules/lib/inv.md#take_allchest-need) and [`colony.common.fetch`](modules/colony/common.md#fetchitem-n): `lib.stock.take_up_to(item, n, chests=[...])`.
- [`lib/gather`](modules/lib/gather.md)'s `make`, `gather` and `stone`: `lib.get.get`, which takes a total where these take "`n` more"; `exposed_stone`: `lib.find.exposed`.

### Writing library functions

The library follows a few rules, so that its functions chain into bigger ones (the plan is on [The script library](../design/library.md)). Keep to them in a module of your own too.

- **One function, one job.** A function does one thing and says in its docstring what it needs and what it gives: `fell(base)` fells a tree; it does not fetch an axe.
- **Finders and doers.** As with senses and actions, a *finder* takes no ticks and gives a value or `None`, such as `next_tree()`. A *doer* takes ticks and gives a [`result()`](modules/lib/result.md) dict: `ok`, `reason`, `at`, `missing`.
- **Preconditions are the caller's.** A doer assumes what it needs (an axe held, a pickaxe of the right tier, room in the inventory) and fails with a reason when it is not there, instead of fetching it. Fetching what is needed is left to one place, the caller or a resolver, so each function stays small.
- **Doers that obtain things ensure totals.** "Get one stone pickaxe" means "carry at least one": called again, it does nothing, so running it twice (after a reload, a death or a [`retry`](modules/lib/retry.md)) is safe. Low-level gatherers such as `logs(n)` take "n more", as they are only called through one that ensures totals.
- **Layers import downwards.** The modules form layers, from primitives such as `lib/result`, `lib/area` and `lib/retry` up to skills such as `lib/trees` and `lib/shaft`. A module imports only modules of a layer below its own, never one of its own layer or above, so there are no import cycles (a cycle fails the script, see [When an import fails](../language/modules.md#when-an-import-fails)).
- **What it borrowed it gives back.** Anything taken from a chest goes through [`lib/borrow`](modules/lib/borrow.md), which remembers it in `memory`; `give_back()` returns it at the end of a job, used or not. Taking from the bot's Colony Core is not borrowing: colony storage is the colony's own (see [`lib/stock`](modules/lib/stock.md)).
- **Tested and documented.** Each function has a game test or a unit test, and each module a page under `reference/modules/lib/`.

## Notes on actions

The block actions (`mine`, `place`, `use`, `put`, `take`, and `craft` and `smelt` at a station given by position) walk, like `goto`, to where the bot sees the block within 4 blocks of its eyes, then work on it. Placing and using go through the game's own code with a stand-in player at the bot, so blocks and items behave as for a player: a bucket fills, a hoe wears down.

A farm needs no helpers of its own: `use(soil, "#minecraft:hoes")` tills, `place(soil.up(), "wheat_seeds")` plants, `block(soil.up()).state["age"]` tells ripe wheat (7) and `mine` harvests it; [`lib/farm`](modules/lib/farm.md) does that over a field.

[Writing a reference page](template.md) describes how a page is laid out and where it goes.
