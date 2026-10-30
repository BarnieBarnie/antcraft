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
| [`goto`](movement/goto.md) | `goto(pos, near=0)` | action: walk to within `near` blocks, digging, pillaring and bridging as needed |
| [`travel`](movement/travel.md) | `travel(portal)` | action: go through the nether portal at `portal` to the other dimension |
| [`reachable`](movement/reachable.md) | `reachable(pos, near=0)` | whether the bot could walk to within `near` blocks of `pos`; plans without moving (may pause) |
| [`pos`](movement/pos.md) | `pos()` | the bot's position, with its dimension |
| [`Pos`](types/pos.md) | `Pos(x, y, z, dimension=None)`, `Pos(position)` | a position made from ints, a tuple or another `Pos` |
| [`home`](movement/home.md) | `home()` | the bot's home, with its dimension |
| [`set_home`](movement/set_home.md) | `set_home(pos)` | sets the bot's home |
| [`home_dimension`](movement/home_dimension.md) | `home_dimension()` | the id of the dimension of the bot's home |
| [`dimension`](movement/dimension.md) | `dimension()` | the id of the dimension the bot is in, such as `"minecraft:overworld"` |
| [`portals`](movement/portals.md) | `portals()` | both ends of the nether portals the bot went through, newest first |

## The world around

| Builtin | Signature | Does |
|---|---|---|
| [`block`](world/block.md) | `block(pos)` | the [`Block`](types/block.md) at `pos`, or `None` where it is not loaded |
| [`find_nearest`](world/find_nearest.md) | `find_nearest(what, radius=32)` | the nearest matching block's position, or `None` (may pause) |
| [`scan`](world/scan.md) | `scan(what, radius=16, limit=64)` | the positions of matching blocks, nearest first (may pause) |
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
| [`put`](crafting/put.md) | `put(container, item, n=None)` | action: put items into a container |
| [`take`](crafting/take.md) | `take(container, item, n=None)` | action: take items out of a container |
| [`craft`](crafting/craft.md) | `craft(item, n=1, table=None)` | action: craft from carried items |
| [`smelt`](crafting/smelt.md) | `smelt(item, n=1, furnace=None, fuel=None)` | action: smelt carried items in a furnace, blast furnace or smoker |
| [`needs`](crafting/needs.md) | `needs(item, n=1)` | the raw items still to gather and the craft and smelt steps to make an item |

## Building

| Builtin | Signature | Does |
|---|---|---|
| [`build`](building/build.md) | `build(blueprint, at, turn=0, mirror=False, job=None)` | action: build a blueprint from carried blocks |
| [`blueprint`](building/blueprint.md) | `blueprint(id, at=None, turn=0, mirror=False)` | a blueprint's size, role, materials and spots as plain data |

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
- [`lib/craft`](modules/lib/craft.md): `craft_all(item, n=1, table=None, furnace=None)`, `craft_here(item, n=1)`, `smelt_here(item, n=1, fuel=None)`, `obtain(item, n=1, chests=None)`, `ensure_tool(kind, chests=None)`.
- [`lib/retry`](modules/lib/retry.md): `retry(action, args, tries=5, again=None, delay=4)`.
- [`lib/inv`](modules/lib/inv.md): `take_all(chest, need)`, `store_all(chest, keep=None)`.
- [`lib/trees`](modules/lib/trees.md): `next_tree(radius=32, where=None)`, `is_tree(base)`, `fell(base, replant=True)`.
- [`lib/area`](modules/lib/area.md): `box(a, b)`, `clear(a, b)`, `fill(a, b, item)`.
- [`colony/common`](modules/colony/common.md): `core()`, `in_colony(p)`, `store(item)`, `fetch(item, n)`, shared by the [default colony scripts](../features/colony-scripts.md).

## Notes on actions

The block actions (`mine`, `place`, `use`, `put`, `take`, and `craft` and `smelt` at a station given by position) walk, like `goto`, to where the bot sees the block within 4 blocks of its eyes, then work on it. Placing and using go through the game's own code with a stand-in player at the bot, so blocks and items behave as for a player: a bucket fills, a hoe wears down.

A farm needs no helpers of its own: `use(soil, "#minecraft:hoes")` tills, `place(soil.up(), "wheat_seeds")` plants, `block(soil.up()).state["age"]` tells ripe wheat (7) and `mine` harvests it (see `colony.farmer`).

[Writing a reference page](template.md) describes how a page is laid out and where it goes.
