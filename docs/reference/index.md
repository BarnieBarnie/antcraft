# API reference

!!! note
    Pages per builtin come later (#403). Until then, this page holds the tables of the bot API.

Names are `snake_case`. The API is available as builtins in every script (no import needed). Positions are `Pos` values; anything that takes a position also takes a tuple `(x, y, z)`.

## Types

- **`Pos`**: `x`, `y`, `z`; `+` and `-` with another `Pos` or tuple; `up(n=1)`, `down`, `north`, `south`, `east`, `west`; `distance(other)`. Usable as a dict key. `dimension` is the id of the dimension a position from `pos()`, `home()` or `portals()` is in, such as `"minecraft:overworld"`, and `None` for a position the script made, which means the dimension the bot is in (a tuple has none either). Every function that takes a position refuses one whose `dimension` is not the bot's: an action fails at once with `wrong_dimension` (`goto`, `travel`, `mine`, `place`, `use`, `put`, `take`, and `craft`'s `table` and `smelt`'s `furnace`), `reachable` gives `False`, `block`, `container`, `mineshaft` and `mineshaft_at` give `None` and `ore_vein` gives `[]`. Only `set_home` takes one from any dimension, and `distance` and `+`, `-` work on the coordinates alone. A `Pos` made from another with `+`, `-` or a step keeps its dimension (the left one's, if both have one). The dimension takes no part in `==` or printing: positions compare by their coordinates, so `pos() == home()` is true at the home's coordinates in any dimension; compare `dimension()` with `home_dimension()` too. It is saved with the script and in `memory`.
- **`Block`**: `id` (`"minecraft:oak_log"`), `pos`, `is_air`, `is_fluid`, `falls` (a block that falls when nothing holds it up: gravel, sand, concrete powder, an anvil), `tags` (list of selectors such as `"#minecraft:logs"`), `state` (a dict of the block state's properties: an int, a bool or a name, such as `{'age': 7}` for ripe wheat or `{'axis': 'y'}` for a log standing up). A snapshot of the block when it was read.
- **`Stack`**: `item` (`"minecraft:oak_log"`), `count`. A snapshot.
- **`Entity`**: `id` (UUID string), `type` (`"minecraft:cow"`), `pos`, `health` (`None` for an entity without health), `valid` (false once it is gone: dead, picked up or unloaded), `item` (the `Stack` of a dropped item, `None` for other entities). Read live; equal by UUID.
- **`Result`**: returned by every action. Truthy when the action succeeded. `ok` (bool), `reason` (a string code, `None` on success), `value` (what the action produced, if anything, for example how many items `craft` made).

## Selectors

Where an item or block is asked for, a string selects it: `"oak_log"` (namespace `minecraft:` assumed), `"minecraft:oak_log"`, a tag `"#minecraft:logs"` or `"#c:ores/iron"`, or a list (or tuple) of these. A malformed selector, or a name or tag the game does not know, stops the script with an error at its line (`unknown block 'minecraft:oak_lgo'`).

## Senses (instant, do not pause)

| Function | Returns |
|---|---|
| `me()` | the bot's own `Entity` |
| `pos()` | the bot's position, with its `dimension` |
| `health()`, `max_health()` | numbers |
| `inventory()` | list of `Stack` |
| `count(item)` | how many matching items the bot carries |
| `free_slots()` | how many of the bot's inventory slots (not its hand) are empty |
| `has(item, n=1)` | `count(item) >= n` |
| `matches(item, selector)` | whether an item id or a `Stack` is one the selector selects: `matches(s, "#minecraft:logs")` for a `Stack` from `container(p)`. A malformed item id (`'Oak Log' is not an item id: ...`), an unknown item or a bad selector stops the script with an error |
| `held()` | the `Stack` in its hand or `None` |
| `block(pos)` | `Block` (unloaded positions give `None`) |
| `find_nearest(what, radius=32)` | nearest matching block's `Pos` the bot can see from its loaded surroundings, or `None` |
| `scan(what, radius=16, limit=64)` | list of matching `Pos`, nearest first |
| `entities(what=None, radius=16)` | list of `Entity` nearest first; `what` is an entity type or tag |
| `items_near(item=None, radius=16)` | list of dropped item `Entity` |
| `container(pos)` | list of `Stack` in a chest, barrel or furnace, or `None` |
| `time()`, `is_day()` | world time of day |
| `home()` | the bot's home `Pos`, with the home's `dimension`: where it was first spawned, until `set_home` changes it |
| `set_home(pos)` | sets the home, in the `dimension` of `pos`, or the dimension the bot is in when `pos` has none (both saved with the bot); returns nothing |
| `dimension()`, `home_dimension()` | the id of the dimension the bot is in, and of its home's, such as `"minecraft:overworld"` |
| `portals()` | list of `Pos`, each with its `dimension`: both ends of the nether portals the bot went through, newest first (see Travelling between dimensions) |
| `deaths()` | how often the bot has died |
| `last_death()` | where the bot last died, a `Pos` with its dimension, or `None` if it never died |
| `blueprint(id, at=None, turn=0, mirror=False)` | a blueprint as plain data, or `None` for an id the server has no blueprint for: a dict with `size` (a tuple `(x, y, z)`), `role` (`"storage"`, `"farm"`, `"generator"` or `None`), `materials` (a dict of item id to how many `build` places) and `chests` and `work_spots` (lists of `Pos` relative to the origin, unturned), so a hauler can fetch materials before the builder starts. Given `at` (and `turn` and `mirror`, as `build` takes them), `chests` and `work_spots` are where a copy built there has them, in the dimension of `at` |
| `colony()` | the bot's colony as a dict, or `None` for a bot in no colony: `core` (a `Pos` with the core's `dimension`), `radius`, `members` (list of bot UUID strings) and `owner` (a player's UUID string, or `None`). Plain data, a snapshot. A member in another dimension than its core (it went through a portal or respawned at a home elsewhere) stays a member: `colony()` and `colony_memory` work the same there, and `core.dimension` tells the script to travel first, as `put` and `take` at the core fail with `wrong_dimension` until it does |
| `mineshaft(levels, near=None, branches=4, length=16)` | where a ladder shaft of `branches` branches a side, each `length` long (as `mineshaft_at` takes them), down to the heights `levels` can go, nearest first beside `near` (default the bot's position), its top on the ground of its column nearest `near`'s height (at most 16 blocks up or down; a spot without ground in reach is skipped), or `None` where no spot is diggable: the column runs into no fluid, bedrock or unloaded block down to the first level, nor does a hall, and neither digs next to a nether portal. Levels at or under bedrock in the column, above the top, or less than 2 under the level above are left out. A dict of plain data: `top` (the `Pos` the column starts under), `hatch` (where the trapdoor goes, in the ground over the top ladder), `levels` (the heights kept, deepest last), `level` (a dict of height to `landing`, `chest`, `hall`, a list of `Pos` from the landing outwards, and `branches`, a list of the first round of branches off the hall, each a list of `Pos`, feet and head), `ladders` (list of `[pos, facing]`, top first) and `torches` (list of `Pos`). Reads loaded blocks only and charges one instruction per 8 blocks read; shares the block search budget with `scan` and `find_nearest` (a site found counts 700 blocks for its dict), so a search it cannot finish this tick (one where no spot fits reads up to some 300,000 blocks) goes on over the next ticks, pausing the script |
| `mineshaft_at(top, levels, round=0, branches=4, length=16)` | the same dict for a shaft already started at `top`, without looking at the world: a script that kept `top` and `levels` in `memory` gets its shaft back. `branches` is the number of branches each side of a hall (3 blocks apart, so the hall is 3 times as long), `length` how long a branch is, and `round` which round of branches `branches` lists: round `n` carries each branch on where round `n - 1` ends. Each level also has `torches`, the torch spots of its hall and of those branches |
| `ore_vein(pos)` | the blocks of the ore vein at `pos`: `pos` and the blocks of the same ore (deepslate iron is iron) that touch it face to face, at most 32, each after one of them it touches, from `pos` out; `[]` where `pos` is no ore. Reads loaded blocks only and charges 10 instructions and one per 8 blocks read |
| `reachable(pos, near=0)` | `True` if the bot could walk to within `near` blocks of `pos` from where it stands, `False` if not; plans without moving (costs pathfinding budget, may pause) |

Details: `inventory()` lists the held stack first, then the inventory; `count` and `has` count both. The radius of `find_nearest` and `scan` is at most 32, that of `entities` and `items_near` at most 64; distances are measured from the block the bot stands in, and `entities` leaves the bot itself out. `find_nearest` and `scan` read only loaded blocks, nearest first (ties by y, x, z), and stop once `limit` matched. They charge the script one instruction per 8 blocks read and share a server-wide budget of 32768 blocks a tick; a search the budget cannot finish this tick goes on over the next ticks, pausing the script (a full search of radius 32 reads some 137,000 blocks). `time()` is the time of day, 0 to 23999. `reachable` plans the path `goto(pos, near)` would take, digging and pillaring as it may with what the bot carries, but over loaded blocks only: it loads no chunks, a position in an unloaded chunk is `False`, and so is one past what a single plan searches (4000 nodes), even where `goto`, which plans on as it walks and loads chunks, might get there. It charges the script one instruction per node searched and shares the server's pathfinding budget of 2000 nodes a tick with every bot's walking; a plan the budget cannot finish this tick goes on over the next ticks, pausing the script.

## Actions (take ticks, return `Result`)

| Function | Does |
|---|---|
| `goto(pos, near=0)` | walk to within `near` blocks, digging (what it digs goes into the inventory), pillaring and bridging as the pathfinder allows. When the next stretch of the path enters an unloaded chunk, the bot loads it (see Chunk loading) and plans on; if it cannot be loaded, `goto` stops with `not_loaded`. A `pos` whose `dimension` is not the bot's, such as `home()` in another dimension than the home's, fails at once with `wrong_dimension` (as for every function that takes a position, see Types); a tuple or a `Pos` without a dimension goes anywhere in the bot's dimension |
| `travel(portal)` | walk into the nether portal at `portal` (any of its blocks, such as `find_nearest("nether_portal")` gives) and go through it to the other dimension; ok once the bot is there, `timeout` when it stood in the portal a second without going, `no_portal` when the block at `portal` is loaded and is no nether portal (a remembered one that was broken: the bot forgets it). See Travelling between dimensions |
| `mine(pos)` | walk into reach and break the block with the best tool carried; drops go straight into the inventory |
| `place(pos, item, facing=None, half=None)` | walk into reach, hold the block item and place it; `facing` is `"north"`, `"south"`, `"east"`, `"west"`, `"up"` or `"down"`; `half`, `"top"` or `"bottom"`, for a block with a half (a trapdoor, stairs, a slab). Only air or a block that may be replaced (grass, water) is placed in, so a slab placed onto a slab is `blocked`, not made a double slab |
| `pick_up(what=None, radius=8)` | walk to the dropped items `what` selects within `radius` (at most 16), nearest first, and take them; `value` is how many it took. Items it cannot walk to are skipped: it fails with `out_of_reach` only when it took none |
| `equip(item)` | hold an item (at once) |
| `use(pos, item=None)` | walk into reach and use the held item, or first hold `item`, on a block as a player's right click does (bucket, hoe, bone meal, flint and steel; with an empty hand, the block itself, such as a lever) |
| `attack(entity)` | fight until the target is dead, gone, or the bot must flee |
| `put(container, item, n=None)` | walk into reach and put up to `n` items into a chest, barrel, furnace or other container (all matching when `n` is `None`); `value` is how many |
| `take(container, item, n=None)` | walk into reach and take up to `n` items out (all matching when `n` is `None`); `value` is how many |
| `craft(item, n=1, table=None)` | craft by one recipe from carried items until at least `n` items are made; `value` is how many it made. 2x2 recipes are crafted anywhere; 3x3 recipes need a crafting table at `table` (walked to) or within reach |
| `smelt(item, n=1, furnace=None, fuel=None)` | load up to `n` of the carried input `item` into the furnace, blast furnace or smoker at `furnace` (walked to) or one within reach that cooks it, burn `fuel` (default: the best fuel carried, coal, then charcoal, then logs), wait by it until done and take the output; `value` is how many items it took out |
| `drop(item, n=None)` | toss up to `n` items (all matching when `n` is `None`), the held ones first; `value` is how many. The bot does not pick up again by itself what it dropped, though `pick_up` takes it |
| `build(blueprint, at, turn=0, mirror=False, job=None)` | build the blueprint with id `blueprint` (`"antcraft:starter_house"`) with its lowest corner at `at`, mirrored left to right if `mirror`, then turned `turn` quarters clockwise, as the Blueprint item's ghost shows it. Layer by layer from the bottom, the bot walks into reach of each block the world does not match yet, breaks what is in the way (drops go into the inventory) and places the block, in the blueprint's block state, from what it carries; a fluid is poured from its bucket once the blocks beside and under it stand. A block it cannot reach from the ground it reaches standing on blocks already built, or by pillaring up with dirt it carries (never where the blueprint puts a block), and once the blueprint stands, or the build stops short of it, it takes that dirt down again (also after a save and load); a build cancelled before that (a reflex, `stop`) leaves the dirt with the bot for its next `build` to take down, wherever that build is; five minutes on, and every five minutes after, the bot takes down at once what of it is in its reach, into its inventory, so the next build walks only to the rest. Its walks break a block of the blueprint already in place only to get to a block no other walk gets to, and place it again after; walks to the blocks after that keep them again. Another bot standing where a block goes steps aside when asked (see Reflexes; `job`, the id of a job taken off the job board that the build is for, sets how long an idle one keeps off; its own action ends with `interrupted`), and the building bot steps off a spot itself. `value` is how many blocks it placed. Blocks already right count as done, so `build` called again, such as after `missing_ingredients`, goes on where it stopped |
| `wait(ticks)` | do nothing |
| `say(text)` | show text above the bot and in its owner's chat |
| `stop()` | end the script |

The block actions (`mine`, `place`, `use`, `put`, `take`, and `craft` and `smelt` at a station given by position) walk, like `goto`, to where the bot sees the block within 4 blocks of its eyes, then work on it. Which reasons each can return:

| Action | Reasons |
|---|---|
| `mine` | `target_gone` (air or a fluid there, or it went while the bot dug), `protected`, `no_tool` (no carried tool makes it drop anything, such as stone without a pickaxe), `out_of_reach`, `no_space` (the block is mined but drops that did not fit lie on the ground), `not_loaded`, `timeout` |
| `place` | `no_item` (no block item `item` selects), `protected`, `blocked` (a block that cannot be replaced is there, or an entity stands there, or the block cannot stay there), `out_of_reach`, `not_loaded`, `timeout` |
| `use` | `no_item`, `protected`, `blocked` (the use did nothing, such as a hoe on stone), `out_of_reach`, `not_loaded`, `timeout` |
| `pick_up` | `target_gone` (no such item around, or it went before the bot took one), `no_space`, `out_of_reach` (none taken, and some could not be walked to), `not_loaded`, `timeout` |
| `build` | `no_blueprint` (no blueprint with that id, or its structure is missing), `missing_ingredients` (it carries no block for the next step; `value` is how many it placed), `protected`, `blocked` (a player or mob still stands where a block goes after two seconds, a fluid is in the way, or the bot could not walk into reach, such as a block too high with no dirt to pillar up with), `not_loaded`, `timeout` |
| `equip`, `drop` | `no_item` |
| `put` | `no_item` (it carries none), `target_gone` (no container there), `no_space` (the container filled up; `value` is how many went in), `protected`, `out_of_reach`, `not_loaded`, `timeout` |
| `take` | `no_item` (the container holds none), `target_gone`, `no_space` (the bot's inventory and hand filled up), `protected`, `out_of_reach`, `not_loaded`, `timeout` |
| `craft` | `no_recipe` (no crafting recipe makes `item`), `missing_ingredients` (the bot does not carry what a recipe needs, or ran out before `n` were made; `value` is how many it made), `no_table` (a 3x3 recipe and no crafting table at `table` or within reach), `no_space` (what it made does not fit), and at a `table` `protected`, `out_of_reach`, `not_loaded`, `timeout` |
| `smelt` | `no_recipe` (no furnace, blast furnace or smoker cooks `item`, or the one at `furnace` does not, such as ore in a smoker), `missing_ingredients` (none of `item` carried, or no fuel to burn when the fire goes out), `no_furnace` (no furnace, blast furnace or smoker at `furnace`, or none within reach that cooks `item`), `blocked` (the furnace holds another input or output), `no_space` (the output does not fit and stays in the furnace), `timeout` (the furnace made nothing for 1200 ticks), `protected`, `out_of_reach`, `not_loaded` |

`protected` means the block may not be changed: outside the world's build height or border, under the server's spawn protection, and for `mine` a block that cannot be broken, such as bedrock. `craft` and `smelt` use carried items, the held stack included. When `item` selects several carried items, `smelt` without `furnace` takes the first one a station within reach cooks. `smelt` puts in one fuel item at a time, when the fire goes out, so it burns no more than it needs; when it gives up it takes back what is left of the input. `put`, `take` and `craft` act in the tick the bot is in reach; `smelt` waits by the furnace, about 10 seconds an item (5 in a blast furnace or smoker).

Placing and using go through the game's own code with a stand-in player at the bot, so blocks and items behave as for a player: a bucket fills, a hoe wears down.

A farm needs no helpers of its own: `use(soil, "#minecraft:hoes")` tills, `place(soil.up(), "wheat_seeds")` plants, `block(soil.up()).state["age"]` tells ripe wheat (7) and `mine` harvests it (see `colony.farmer`).

## Reason codes

`unreachable`, `wrong_dimension` (an action given a `Pos` of another dimension, such as `goto(home())` from another dimension than the home's), `no_path_budget` (only seen if a script asks again in the same tick), `out_of_reach`, `not_loaded`, `no_tool` (needs a better tool), `no_item`, `no_space` (inventory or container full), `no_recipe`, `missing_ingredients`, `no_blueprint`, `no_table`, `no_furnace`, `blocked` (something is in the way), `protected` (the block may not be changed, for example by claim rules), `target_gone`, `interrupted` (a reflex took over; any action that takes ticks, `wait` too, can end with it, see Reflexes), `stopped`, `timeout` (also `travel` when the portal did not take the bot), `no_portal` (`travel` to a position with no nether portal; a remembered one there, or within 16 blocks of it, is forgotten).

## Memory

`memory` is a dict that belongs to the bot and is saved with it. It survives restarts and script edits. Values must be plain data: numbers, strings, booleans, `None`, `Pos`, and lists, tuples and dicts of these. Storing anything else (a function, a `Stack`, an `Entity`, or a list holding one) stops the script with an error at the line of the store. A list or dict already in `memory` can still be changed in place; a value that is no longer plain data when the bot is saved is left out of the save, with a warning in the bot's log.

```python
if "chest" not in memory:
    memory["chest"] = find_nearest("chest")
```

`colony_memory` is a dict shared by every member of the bot's colony, under the same plain-data rules, and saved with the colony; it is `None` for a bot in no colony. What one member stores the others see at once. It is never saved with a bot: a bot saved while its script holds `colony_memory`, in the global or a variable of its own, resumes with the colony's dict as it is then. A list or dict taken out of it into a variable is the bot's own copy after a resume. The Colony Core stores items like a chest, so `container`, `put` and `take` work on `colony()["core"]`.

```python
c = colony()
if c is not None and "chests" not in colony_memory:
    colony_memory["chests"] = [c["core"]]
```
