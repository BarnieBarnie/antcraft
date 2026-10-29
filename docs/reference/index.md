# API reference

Every builtin a script can call, grouped by what it is for. The API is available as builtins in every script (no import needed); names are `snake_case`. Positions are [`Pos`](types/pos.md) values; anything that takes a position also takes a tuple `(x, y, z)`.

Senses are done at once and return a value. Actions take ticks, pause the script until they are done and return a [`Result`](types/result.md). Builtins whose name is not a link have no page of their own yet.

## Types

- [`Pos`](types/pos.md): a block position, with its dimension.
- [`Block`](types/block.md): a block as `block(pos)` reads it.
- [`Stack`](types/stack.md): an item and how many.
- [`Entity`](types/entity.md): a mob, player or dropped item.
- [`Result` and reason codes](types/result.md): what an action returns, and why it failed.
- [Selectors](types/selectors.md): the strings that pick items and blocks.
- [`memory` and `colony_memory`](types/memory.md): the dicts a bot and its colony keep.

## Movement

| Builtin | Signature | Does |
|---|---|---|
| `goto` | `goto(pos, near=0)` | action: walk to within `near` blocks, digging, pillaring and bridging as needed |
| `travel` | `travel(portal)` | action: go through the nether portal at `portal` to the other dimension |
| `reachable` | `reachable(pos, near=0)` | whether the bot could walk to within `near` blocks of `pos`; plans without moving (may pause) |
| `pos` | `pos()` | the bot's position, with its dimension |
| `home` | `home()` | the bot's home, with its dimension |
| `set_home` | `set_home(pos)` | sets the bot's home |
| `home_dimension` | `home_dimension()` | the id of the dimension of the bot's home |
| `dimension` | `dimension()` | the id of the dimension the bot is in, such as `"minecraft:overworld"` |
| `portals` | `portals()` | both ends of the nether portals the bot went through, newest first |

## The world around

| Builtin | Signature | Does |
|---|---|---|
| `block` | `block(pos)` | the [`Block`](types/block.md) at `pos`, or `None` where it is not loaded |
| `find_nearest` | `find_nearest(what, radius=32)` | the nearest matching block's position, or `None` (may pause) |
| `scan` | `scan(what, radius=16, limit=64)` | the positions of matching blocks, nearest first (may pause) |
| `entities` | `entities(what=None, radius=16)` | the [`Entity`](types/entity.md) values around, nearest first |
| `items_near` | `items_near(item=None, radius=16)` | the dropped items around, nearest first |
| [`time`](world/time.md) | `time()` | the time of day, 0 to 23999 |
| `is_day` | `is_day()` | whether it is day |

## Inventory

| Builtin | Signature | Does |
|---|---|---|
| `inventory` | `inventory()` | the carried [`Stack`](types/stack.md) values, the held one first |
| `held` | `held()` | the `Stack` in the bot's hand, or `None` |
| `count` | `count(item)` | how many matching items the bot carries |
| `has` | `has(item, n=1)` | whether the bot carries at least `n` matching items |
| `free_slots` | `free_slots()` | how many inventory slots are empty |
| `matches` | `matches(item, selector)` | whether an item id or a `Stack` is one the [selector](types/selectors.md) selects |
| [`equip`](inventory/equip.md) | `equip(item)` | hold a carried item |
| `drop` | `drop(item, n=None)` | toss up to `n` matching items |
| `pick_up` | `pick_up(what=None, radius=8)` | action: walk to dropped items and take them |

## Working blocks

| Builtin | Signature | Does |
|---|---|---|
| `mine` | `mine(pos)` | action: break the block with the best tool carried |
| `place` | `place(pos, item, facing=None, half=None)` | action: place a block |
| `use` | `use(pos, item=None)` | action: use the held item, or the block itself, on a block as a right click does |

## Containers and crafting

| Builtin | Signature | Does |
|---|---|---|
| `container` | `container(pos)` | the `Stack` values in a chest, barrel or furnace, or `None` |
| `put` | `put(container, item, n=None)` | action: put items into a container |
| `take` | `take(container, item, n=None)` | action: take items out of a container |
| `craft` | `craft(item, n=1, table=None)` | action: craft from carried items |
| `smelt` | `smelt(item, n=1, furnace=None, fuel=None)` | action: smelt carried items in a furnace, blast furnace or smoker |

## Building

| Builtin | Signature | Does |
|---|---|---|
| `build` | `build(blueprint, at, turn=0, mirror=False, job=None)` | action: build a blueprint from carried blocks |
| `blueprint` | `blueprint(id, at=None, turn=0, mirror=False)` | a blueprint's size, role, materials and spots as plain data |

## Mining

| Builtin | Signature | Does |
|---|---|---|
| `mineshaft` | `mineshaft(levels, near=None, branches=4, length=16)` | where a ladder shaft can go, and its plan (may pause) |
| `mineshaft_at` | `mineshaft_at(top, levels, round=0, branches=4, length=16)` | the plan of a shaft already started at `top` |
| `ore_vein` | `ore_vein(pos)` | the blocks of the ore vein at `pos` |

## Colony and jobs

| Builtin | Signature | Does |
|---|---|---|
| `colony` | `colony()` | the bot's colony as a dict, or `None` |
| `post_job` | `post_job(kind, data=None)` | post a job on the colony's job board |
| `take_job` | `take_job(kind=None)` | take a job off the job board |
| `jobs` | `jobs(kind=None)` | the jobs on the job board |
| `finish_job` | `finish_job(id)` | mark a taken job done |
| `drop_job` | `drop_job(id)` | put a taken job back on the board |

## The bot and its output

| Builtin | Signature | Does |
|---|---|---|
| `deaths` | `deaths()` | how often the bot has died |
| `last_death` | `last_death()` | where the bot last died, or `None` |
| `say` | `say(text)` | show text above the bot and in its owner's chat |
| `print` | `print(*values)` | write a line to the bot's log, always shown |
| `debug` | `debug(*values)` | write a line to the bot's log at debug level |
| `info` | `info(*values)` | write a line to the bot's log at info level |
| `warn` | `warn(*values)` | write a line to the bot's log at warn level |
| `error` | `error(*values)` | write a line to the bot's log at error level |
| `wait` | `wait(ticks)` | action: do nothing for `ticks` ticks |
| `stop` | `stop()` | end the script |

## Python builtins

| Builtin | Signature | Does |
|---|---|---|
| `abs` | `abs(x)` | the absolute value |
| `all` | `all(iterable)` | whether every element is truthy |
| `any` | `any(iterable)` | whether any element is truthy |
| `bool` | `bool(x=False)` | `x` as a bool |
| `dict` | `dict(mapping_or_pairs=None, **kwargs)` | a new dict |
| `enumerate` | `enumerate(iterable, start=0)` | pairs of index and element |
| `float` | `float(x=0.0)` | `x` as a float |
| `int` | `int(x=0, base=10)` | `x` as an int |
| `isinstance` | `isinstance(obj, class_or_tuple)` | whether `obj` is of the type, or one of the types |
| `len` | `len(obj)` | the number of elements |
| `list` | `list(iterable=())` | a new list |
| `max` | `max(...)` | the largest element |
| `min` | `min(...)` | the smallest element |
| `range` | `range(stop)`, `range(start, stop, step=1)` | a range of ints |
| `reversed` | `reversed(sequence)` | the elements back to front |
| `round` | `round(number, ndigits=None)` | `number` rounded |
| `sorted` | `sorted(iterable, *, key=None, reverse=False)` | a sorted list |
| `str` | `str(object="")` | `object` as a string |
| `sum` | `sum(iterable, start=0)` | the sum |
| `tuple` | `tuple(iterable=())` | a new tuple |
| `zip` | `zip(*iterables)` | tuples of the elements side by side |

## Notes on actions

The block actions (`mine`, `place`, `use`, `put`, `take`, and `craft` and `smelt` at a station given by position) walk, like `goto`, to where the bot sees the block within 4 blocks of its eyes, then work on it. Placing and using go through the game's own code with a stand-in player at the bot, so blocks and items behave as for a player: a bucket fills, a hoe wears down.

A farm needs no helpers of its own: `use(soil, "#minecraft:hoes")` tills, `place(soil.up(), "wheat_seeds")` plants, `block(soil.up()).state["age"]` tells ripe wheat (7) and `mine` harvests it (see `colony.farmer`).

[Writing a reference page](template.md) describes how a page is laid out and where it goes.
