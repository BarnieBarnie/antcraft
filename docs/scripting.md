# Scripted bots: design

Status: draft, 2026-10-27. This replaces the goal-driven colony as the direction of the mod; the README roadmap is updated once the first milestone lands.

## Why

The goal-driven bots grew large and still do not finish hard work reliably: the mineshaft has never once been built right in a play-test. Every new case went into a fixed algorithm, and every player wants something different from it.

From now on the mod gives bots **capabilities** and the player gives them **behaviour**:

- The mod ships a small set of robust built-in actions and senses (walk somewhere, mine a block, craft an item, find the nearest tree).
- Players write scripts in a small subset of Python that combine those into anything they want, and share them as plain files.
- The colony behaviour we were building comes back later as a library of default scripts, written against the same API as everyone else's.

Principles:

1. **Small parts that stack.** Each built-in does one thing well and says clearly when it could not. Like Unix tools, the power is in combining them.
2. **Actions are safe, scripts are free.** A script cannot walk its bot into lava or starve it. Safety lives in the engine, so a stranger's script is safe to run.
3. **Failures are visible.** Every action returns a result with a reason, and every script error names its file and line.
4. **Built up in steps.** Each action and each language feature lands with its own tests, and a bot is useful from the first milestone.

## Overview

```
 player scripts (.py)          lumberjack.py, miner.py, brother's_farm.py
 default script library        lib/trees.py, lib/shaft.py, lib/mine.py, later colony/*.py
 ───────────────────────────────────────────────────────────────────────────
 script runtime (Java)         parser, compiler, VM, per-tick budget, save/resume
 bot API (Java)                senses (instant) and actions (take ticks)
 reflexes (Java)               always on: hazards, health, drowning
 engine (Java, kept)           PathFinder, PathFollower, BlockBreaker, Crafting, blueprints
```

A bot runs at most one script. Each server tick the bot:

1. runs its reflexes, which may interrupt the current action;
2. advances the current action, if any;
3. if no action is running, runs its script until the script starts an action, waits, finishes, or uses up its instruction budget for this tick.

Everything runs on the server thread. The VM pauses by simply not running more instructions, so no threads, no locks, and the game world is only touched from the server thread.

## The language

Files use the `.py` extension so every editor highlights them, but the language is a documented **subset of Python 3**. Anything outside the subset is a clear compile error ("`class` is not supported"), never silent different behaviour.

### Supported

- **Values:** `int` (64-bit; overflow is an error), `float`, `str`, `bool`, `None`, `list`, `dict` (keys: `str`, `int`, `bool`, `Pos`), `tuple`, and the API's own types (see below).
- **Statements:** assignment (including `+=` and friends, and tuple unpacking `a, b = ...`), `if`/`elif`/`else`, `while`, `for x in ...`, `break`, `continue`, `pass`, `def` with default and keyword arguments, `return`, `assert test, message` (a failed assert stops the script with "assertion failed" and the message), `global`, `import name`, `from name import a, b`.
- **Expressions:** arithmetic (`+ - * / // % **`, unary `-` and `+`), comparisons (chained `a < b < c` too), `and`/`or`/`not`, `in`/`not in`, `is`/`is not` (only for `None`), indexing and slicing, attribute access, calls, list and dict literals, conditional expressions (`a if c else b`), f-strings, list comprehensions.
- **Builtins:** `print`, `debug`, `info`, `warn`, `error` (see Output and logging), `len`, `range`, `min`, `max`, `abs`, `round`, `sum`, `sorted`, `reversed`, `enumerate`, `zip`, `str`, `int`, `float`, `bool`, `list`, `dict`, `isinstance` (for the builtin types), `any`, `all`.
- **Methods:** the common ones of `str` (`split`, `join`, `strip`, `startswith`, `endswith`, `replace`, `lower`, `upper`, `format`), `list` (`append`, `extend`, `pop`, `insert`, `remove`, `index`, `sort`), and `dict` (`get`, `keys`, `values`, `items`, `pop`, `setdefault`).
- **Details:** `sorted`, `min`, `max` and `list.sort` take `key=` (a function defined with `def`, or a builtin such as `len`) and `reverse=`. `dict.keys()`, `values()` and `items()` are live views, as in Python. f-strings and `format` take Python's format specs (`:.2f`, `:>5`, `:,`, `:x`, ...). A spec may hold nested fields, in an f-string (`f"{x:{width}.{digits}f}"`) as in `str.format` (`"{:{}}".format(x, width)`), and an f-string field ending in `=` shows its expression too (`f"{x=}"` gives `x=3`). `str.format` fields are `{}`, `{0}` or `{name}`, each with an optional conversion and spec. `str % args` formats as in Python (`"%d logs" % n`, `"%s at %s" % (a, b)`, `"%(name)s" % d`) with the flags `- + space # 0`, width and precision (`*` too) and the conversions `d i u o x X e E f F g G c s r a %`; any other conversion is an error naming it. A comprehension's variables are its own, as in Python 3.
- **Truthiness** as in Python: `0`, `0.0`, `""`, `[]`, `{}`, `None`, `False` and failed `Result`s are false.

### Not supported (v1)

`class`, `try`/`except`/`raise`, `lambda`, `with`, generators and `yield`, decorators, `async`, set literals and `set`, `*args`/`**kwargs`, nested functions that capture variables (closures), `nonlocal`, the Python standard library. Several may come later. Most of them are needed less than in normal Python, because actions return results instead of raising.

### Errors

A compile error is reported when the script is loaded: `miner.py:12:5: expected ':' after 'if' condition`. A runtime error (`None` has no attribute `x`, index out of range, unknown function) stops the script, is logged at level `error` (so it shows in chat and in the bot log, see Output and logging), and the bot falls back to `idle.py`. Messages name the line and, where possible, hint at the cause: `miner.py:18: chest is None (find_nearest found nothing?)`.

### Output and logging

Every bot keeps a log (`/antcraft bot [bots] log`). Lines have a level: `debug`, `info`, `warn` or `error`. Each line goes to the log, and to the chat of the bot's owner when its level is at or above `scriptChatLevel` (config, default `error`).

| Call | Level | Chat (default) | Log |
|---|---|---|---|
| `print(...)` | always shown | yes | yes |
| `debug(...)` | `debug` | no | yes |
| `info(...)` | `info` | no | yes |
| `warn(...)` | `warn` | no | yes |
| `error(...)` | `error` | yes | yes |
| runtime and compile errors | `error` | yes | yes |
| action failures (`Result` with a reason) | `debug` | no | yes |

`print` is meant for the player, so it always goes to both, whatever the chat level. `error(...)` only reports; it does not stop the script.

The log keeps a bot's last 200 lines and is saved with the bot, so `/antcraft bot log` still shows what happened before a restart. `say(text)` goes to the log as a `print` line and to the owner's chat, and floats above the bot for five seconds; a new `say` replaces it. `/antcraft bot status` shows the last action the script finished with its result, such as `goto(Pos(4, 70, -2)) -> unreachable` or `craft('stick') -> ok, 4`; that result is saved with the bot too.

### Modules

A module is a script of the script folders (see Files and commands), named as scripts are: `trees.py` is `trees`, `lib/trees.py` is `lib.trees`.

- `import trees` runs the top level of `trees.py` and binds the module to `trees`, so its names are `trees.count`, `trees.fell(...)`.
- `import lib.trees` imports `lib` and then `lib.trees`, and binds `lib`; the module is `lib.trees`. A folder without a file of its own (such as `lib/`) is a package that holds its modules.
- `from lib.trees import fell, count` binds the names given. `from lib import trees` binds the module `lib.trees`.
- `import ... as` and `from ... import *` are not supported.

A module's top level runs once per bot run, the first time any module imports it; later imports bind the module already loaded. Each module has its own globals, and a function sees the globals of the module that defined it, wherever it is called from. The builtins, the bot API and `memory` are visible in every module; `ARGS` is a global of the script the bot runs.

A module that is not there fails the script at the line of the import (`No module named 'lib.trees'`), and so does a name a module does not have (`cannot import name 'fel' from 'lib.trees'`). A module that does not compile fails the script with its own file and line (`lib/trees.py:4: ...`), and an error while a module runs names that module's file. Circular imports fail with the cycle: `circular import: lib.a -> lib.b -> lib.a`.

The mod's default library modules live under `lib/` and are copied with `idle.py` (see Files and commands); for now `lib/home.py` (`go_home()`, `at_home()`, `to_home_dimension()`, `portal_out()`), `lib/shaft.py` and `lib/mine.py`. The default colony scripts live under `colony/` (see Colony scripts). A bot imports modules from the library as it was when its script started; `/antcraft script reload` restarts a bot whose script, or a module it imported, changed.

`lib/shaft.py` digs the ladder shaft `mineshaft` describes (see Senses) and takes the bot up and down it:

| Function | |
|---|---|
| `dig_shaft(levels)` | digs the shaft down to the heights `levels`, top down: each block of the column with a ladder in it, a wooden trapdoor facing the ladders shut in the top half of the hatch over them, flush with the ground (bots open it to climb through, mobs and players do not fall in), and at each level its chamber with the chest and a torch. Before it mines a block it stands on it, and once in, it makes the blocks round it solid with a block it dug (cobblestone and the like): water, lava and air are walled off, falling blocks (gravel, sand) dug out first, and what fell in meanwhile is dug again. What flows into a block it dug before it got in, such as a pond beside the hatch, is walled off where it comes in from, and the block drains. A bot places no block where it stands, so the ladders go in two blocks above it as it goes down, and the last two from the deepest chamber, where it ends. A reflex that takes over (a mob, lava) is waited out and the step tried again. Bots fight the hostile mobs they see; a mob that keeps standing where a block goes (an animal, a creeper) stops it with `blocked` after some tries, and `dig_shaft` called again goes on once it moved. Gives a dict: `ok`, `reason` (`None`, or why it stopped: `no_site` where `mineshaft` finds none, `missing_ingredients`, `blocked`, or an action's reason), `at` (the block it stopped at, or `None`) and `missing` (a dict of item to how many it lacks: ladders, a trapdoor (`#minecraft:wooden_trapdoors`), torches and chests for what is left to do, a pickaxe (`#minecraft:pickaxes`), or `minecraft:cobblestone` when it has nothing left to wall off with). It checks what it carries before it digs at all, so a bot short of ladders stops at once and goes on once given them; making what it lacks is the caller's work |
| `to_level(level)`, `to_surface()` | climb to the chamber of a level, or out onto the ground beside the top; give `goto`'s result |
| `shaft(levels=None)` | the shaft as `mineshaft` gives it: the one kept in `memory["shaft"]` (its `top` and `levels`), else a new site for `levels`, which is kept there. Forget it with `memory.pop("shaft")` to dig another |

How far the shaft is dug is read from the world, not kept, so `dig_shaft` called again after a restart, a reload or a failure goes on where it stopped; a bot saved in the middle of it resumes in place (see Save and resume).

`lib/mine.py` mines the levels of the shaft `lib/shaft.py` dug, the one in `memory["shaft"]`:

| Function | |
|---|---|
| `mine_level(level, rounds=1)` | digs the hall of `level` from its landing and the next `rounds` rounds of branches off it (see `mineshaft_at`), 2 high, each block from the one before it. It mines every ore vein (`ore_vein`) in the walls and ceiling of what it digs, walls off what each block it digs opens up as `dig_shaft` does, puts torches at the level's torch spots, and puts the ore, the junk (gravel, flint, dirt, granite, diorite, andesite, tuff, calcite, sand, clay), and what else it dug beyond 64 blocks to wall off with, into the landing chest, when its inventory has 3 slots or fewer free and once done. It keeps at least 16 blocks to wall off with, of the junk that walls off (dirt, granite, diorite, andesite, tuff) where it has too few others, as on a level of granite. Where water or lava is in or beside the next block, it seals it and stops that hall or branch there, short of a fluid in the next block or once the block beside one is dug, and goes on with the next branch; a branch whose way in stopped short is left. Gives `dig_shaft`'s dict with `sealed` too, the fluid blocks where a hall or branch stopped: `reason` is `missing_ingredients` without a pickaxe (checked first) or without a block to seal with, `no_space` when the chest is full or putting away frees too little (it carries what it does not put away, such as food), or an action's reason |
| `site(r=0)` | the shaft in `memory["shaft"]` with its branches of round `r`, as `mineshaft_at` gives it. Besides `top` and `levels`, `memory["shaft"]` may hold `branches` and `length` for a shaft of another size |

How many rounds of a level are done is kept in `memory["mine"]` (level to rounds); how far a round is dug is read from the world, so `mine_level` called again goes on where it stopped.

## Runtime

### Pipeline

Source is tokenized (with Python's `INDENT`/`DEDENT` rules), parsed into an AST, checked against the subset, and compiled to a compact bytecode per function. A stack VM with explicit frames runs the bytecode. None of this needs a world, so the whole language is unit tested with plain JUnit. Runtime errors mostly use CPython's messages (`list index out of range`, `'NoneType' object has no attribute 'pos'`) after the file and line, and calls nest at most 200 deep.

### Budget

Each bot gets `scriptInstructionsPerTick` (config, default 2000) VM instructions a tick. A script that uses them up simply continues on the next tick, so `while True: pass` costs a busy bot, not a frozen server. See Config for its limit. A call of a bot API function that is done at once and touches the world costs more than one instruction, about as many as the time it takes: `craft` 40, `drop` 20, `equip` 15, `inventory`, `block` and `container` 30, `count` and `has` 20, `entities` 120, `items_near` 10, `smelt` 60, `colony` 35, `free_slots` 20, `matches` 15, `blueprint` 100, `mineshaft_at` 1500, `take_job` 30, `drop_job` and `finish_job` 20, `post_job` 35, and `jobs` 10 and 8 a job it lists (measured in the benchmark, where a plain instruction takes about 20 ns of the tick, the bot's own tick included). So a script that loops on them uses up its budget in about the time a plain loop would. Functions that cost about an instruction (`pos`, `time`, `held`) and actions that pause the script cost one. Senses that scan the world (`scan`, `find_nearest`, `mineshaft`, and `ore_vein`, whose vein is at most 32 blocks) count extra instructions in proportion to the blocks they read, and all but `ore_vein` share a server-wide budget the way pathfinding shares `PathBudget`; a site `mineshaft` finds also counts 700 blocks of that budget for its dict (about its time, #399). The bot benchmark logs each case's average, 99th percentile (p99) and worst tick; judge costs on the average and the p99, as the single worst tick varies some 2 ms between runs of the same code (#399).

### Pausing

A script pauses when it calls an action (until the action ends), calls `wait(ticks)`, or runs out of budget. The action's `Result` is the call's return value when the script continues.

### Save and resume

Because the VM's state is plain data (frames, operand stacks, globals, the call into the running action), a bot saves its running script with the world and resumes on load at the same instruction. What is saved: every frame (its code, by module and place in it, not as code), its locals and operand stack, every module the script imported with its globals, `memory`, loops in progress (iterators and dict views) and how far the running action got. Values are saved as a graph, so a list two variables share comes back as one list. `Pos`, `Stack`, `Block` and `Result` are saved as plain data; an `Entity` by its UUID, found again once the script uses it, and not `valid` while it is not loaded.

The action a script was in starts again on load with the same arguments, as if the script had just called it: `goto` plans its path again from where the bot stands, `mine` walks back to its block, `build` goes on with the blocks still to place, keeping the count of those it placed, and so on. `wait` goes on with the ticks it had left. An action that is done at once when called again gives its result and the script goes on.

The save holds a fingerprint of the compiled code of the script and of each module it imported (which changes with the source, line numbers included). If one of them changed since, is gone or no longer compiles, the script starts again from the top instead, with its arguments and keeping `memory`, and the bot's log says why (`miner.py changed since the save: miner starts again from the top`). So does a script that held a value that cannot be saved. A bot saved before scripts were saved with it runs `idle.py`. A script that is gone from the script folders when the bot loads is not resumed: the bot runs `idle.py`, with a warning in its log. A bot resumes its script on its first tick after loading, once the script folders are read. A bot that goes through a portal resumes at once in the other dimension with the code it ran, also a script not from the library (see Travelling between dimensions). A bot that dies does not resume: it runs its script again from the top when it respawns (see Idle, home and death), also when it died before it resumed the script it was saved running.

## The bot API

Names are `snake_case`. The API is available as builtins in every script (no import needed). Positions are `Pos` values; anything that takes a position also takes a tuple `(x, y, z)`.

### Types

- **`Pos`**: `x`, `y`, `z`; `+` and `-` with another `Pos` or tuple; `up(n=1)`, `down`, `north`, `south`, `east`, `west`; `distance(other)`. Usable as a dict key. `dimension` is the id of the dimension a position from `pos()`, `home()` or `portals()` is in, such as `"minecraft:overworld"`, and `None` for a position the script made, which means the dimension the bot is in (a tuple has none either). Every function that takes a position refuses one whose `dimension` is not the bot's: an action fails at once with `wrong_dimension` (`goto`, `travel`, `mine`, `place`, `use`, `put`, `take`, and `craft`'s `table` and `smelt`'s `furnace`), `reachable` gives `False`, `block`, `container`, `mineshaft` and `mineshaft_at` give `None` and `ore_vein` gives `[]`. Only `set_home` takes one from any dimension, and `distance` and `+`, `-` work on the coordinates alone. A `Pos` made from another with `+`, `-` or a step keeps its dimension (the left one's, if both have one). The dimension takes no part in `==` or printing: positions compare by their coordinates, so `pos() == home()` is true at the home's coordinates in any dimension; compare `dimension()` with `home_dimension()` too. It is saved with the script and in `memory`.
- **`Block`**: `id` (`"minecraft:oak_log"`), `pos`, `is_air`, `is_fluid`, `falls` (a block that falls when nothing holds it up: gravel, sand, concrete powder, an anvil), `tags` (list of selectors such as `"#minecraft:logs"`), `state` (a dict of the block state's properties: an int, a bool or a name, such as `{'age': 7}` for ripe wheat or `{'axis': 'y'}` for a log standing up). A snapshot of the block when it was read.
- **`Stack`**: `item` (`"minecraft:oak_log"`), `count`. A snapshot.
- **`Entity`**: `id` (UUID string), `type` (`"minecraft:cow"`), `pos`, `health` (`None` for an entity without health), `valid` (false once it is gone: dead, picked up or unloaded), `item` (the `Stack` of a dropped item, `None` for other entities). Read live; equal by UUID.
- **`Result`**: returned by every action. Truthy when the action succeeded. `ok` (bool), `reason` (a string code, `None` on success), `value` (what the action produced, if anything, for example how many items `craft` made).

### Selectors

Where an item or block is asked for, a string selects it: `"oak_log"` (namespace `minecraft:` assumed), `"minecraft:oak_log"`, a tag `"#minecraft:logs"` or `"#c:ores/iron"`, or a list (or tuple) of these. A malformed selector, or a name or tag the game does not know, stops the script with an error at its line (`unknown block 'minecraft:oak_lgo'`).

### Senses (instant, do not pause)

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

### Actions (take ticks, return `Result`)

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

### Reason codes

`unreachable`, `wrong_dimension` (an action given a `Pos` of another dimension, such as `goto(home())` from another dimension than the home's), `no_path_budget` (only seen if a script asks again in the same tick), `out_of_reach`, `not_loaded`, `no_tool` (needs a better tool), `no_item`, `no_space` (inventory or container full), `no_recipe`, `missing_ingredients`, `no_blueprint`, `no_table`, `no_furnace`, `blocked` (something is in the way), `protected` (the block may not be changed, for example by claim rules), `target_gone`, `interrupted` (a reflex took over; any action that takes ticks, `wait` too, can end with it, see Reflexes), `stopped`, `timeout` (also `travel` when the portal did not take the bot), `no_portal` (`travel` to a position with no nether portal; a remembered one there, or within 16 blocks of it, is forgotten).

### Memory

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

### Jobs

A colony has a job board, so its members split work by role: one posts "haul these logs", another takes it, and no two take the same job. A script can pause anywhere when it uses up its budget, so a board kept in `colony_memory` could not claim a job in one step; these natives do, at once:

| Call | |
|---|---|
| `post_job(kind, data=None)` | adds an open job: `kind` a string, `data` plain data (a copy is kept); returns its id, an int that counts up per colony. Outside a colony it fails the script at its line |
| `jobs(kind=None)` | the open and taken jobs of `kind` (or of every kind), oldest first, as dicts: `id`, `kind`, `data`, `taker` (the bot's UUID string, or `None` while open) and `poster`. `[]` outside a colony |
| `take_job(kind=None)` | claims the oldest open job of `kind` (or of any kind) for this bot and returns it as `jobs` lists it, or `None` when there is none, or outside a colony |
| `finish_job(id)` | removes the job this bot took, or an open job this bot posted (to cancel it); `True` if it did. Another bot's job stays: `False` |
| `drop_job(id)` | gives a job this bot took back to the board, open again; `True` if it did, `False` for an open job or another bot's |

A job does not stay claimed forever: it goes back to the board when its taker's script ends, fails or is stopped (also by `/antcraft bot stop`, or by starting another script on the bot), when the taker leaves the colony, and when the bot is removed. A bot that dies keeps its jobs: it respawns and runs its script again, and `jobs()` shows it what it held. The board is saved with the colony.

```python
j = take_job("haul")
if j is not None:
    for p in j["data"]:
        take(p, "oak_log")
    put(colony()["core"], "oak_log")
    finish_job(j["id"])
```

### Colony scripts

The mod's default colony scripts do what the goal-driven colony did, with the API every script has, so a player gets a working colony out of the box and an example to change. Each runs on a colony member (`/antcraft bot run colony.lumberjack`, or a Bot Core with `/antcraft core script colony.lumberjack`), works alone, and works with the others through the job board and the core. In no colony, each says so and stops.

| Script | Does |
|---|---|
| `colony.lumberjack` | Fells the trees within the colony's radius: a trunk of logs standing on dirt with leaves at its top, so log walls are left alone. Mines it from the ground up, picks up what fell, plants a sapling it carries where the trunk stood and puts the logs into the core. With no tree left it waits 10 seconds and looks again |
| `colony.hauler` | Takes `haul` jobs and brings the items to the core. Without jobs it waits where it is; a build that needs its spot asks it to make way (see Reflexes) |
| `colony.builder` | Takes `build` jobs: takes the blueprint's materials from the core and builds. While the core lacks some, it posts a `haul` job for each (one per item and build) and tries again every two seconds; alone, it waits for a player to fill the core. A building with a role is added to `colony_memory["buildings"]` (the job's data). Another reason than `missing_ingredients` (such as `blocked`) gives the job back to the board, with a warning in its log, and it tries again 10 seconds later |
| `colony.farmer` | Farms the work spots (the soil blocks) of each building in `colony_memory["buildings"]` whose blueprint has the role `farm`: harvests ripe wheat, carrots, potatoes and beetroot, tills dirt and grass, replants each harvested spot with the same crop and other bare spots with the seeds it carries (wheat first), picks up what lies around and stores the harvest, the seeds the bare spots do not need and any poisonous potato in the core, keeping one carrot and one potato as seed, every 10 seconds. It takes a hoe and the seeds the bare spots need from the core |
| `colony.miner` | Mines through the colony's one shaft, kept in `colony_memory["mineshaft"]`: the first miner claims the site through the board (it posts a `shaft_site` job and takes it; of two posted at once the older wins, and others wait while one is taken), picks a site beside the core (`mineshaft(levels, near=core)`), posts a `shaft` job and digs it with `lib/shaft.py`; once dug, a `mine` job goes on the board for each level. A miner takes a `shaft` job before a `mine` job, mines one round of the level's branches with `lib/mine.py`, takes the ore from the landing chest to the core and posts the level again with the next round, so miners take turns and the level mined least comes first; others wait. Ladders, a wooden trapdoor for the hatch, torches, chests and a pickaxe come from the core; what the core lacks it logs as a warning and tries again 10 seconds later, posting no job for it. Set `colony_memory["mineshaft"]` before the first miner runs to choose the `levels` (default 16, -16 and -54), `branches` and `length` |

Their jobs' data, dicts of plain data:

| Kind | Data |
|---|---|
| `mine` | `level` (a height of the shaft) and `round` (optional, 0: the round of branches to mine). `shaft` and `shaft_site` jobs carry no data |
| `build` | `blueprint` (an id), `at` (the lowest corner), `turn` and `mirror` (optional, as `build` takes them) |
| `haul` | `item` (a selector: an item id, a tag such as `"#minecraft:logs"` or a list of these), `n` (optional; all found without it), `from` (optional: the container to take from; else every chest and barrel within the colony's radius that holds the item, nearest first) and `to` (optional: the container to bring to; else the core). The builder adds `for`, the id of its build job |

```python
post_job("build", {"blueprint": "antcraft:farm", "at": colony()["core"] + (4, -1, 0)})
post_job("haul", {"item": "minecraft:oak_log", "n": 16})
```

They share `colony/common.py`: `core()` (the core, or say and stop outside a colony), `in_colony(pos)`, `store(item)` (put all carried into the core) and `fetch(item, n)` (take from the core up to `n` carried; `item` may be a selector such as `"#minecraft:planks"`).

## Reflexes

Reflexes run every tick in Java, before the script, and cannot be turned off by a script:

- the pathfinder never plans through lava, fire, or a fall that hurts (as today);
- in fire or lava, or next to flowing lava: step aside, to the nearest block within 4 where it can stand clear of fire and lava, through the fire if need be;
- under water with less than `botSurfaceAir` air left (config, default 100 of 300 ticks): swim the shortest way, within 12 blocks, to where its head is out of the water, along under a ceiling if need be;
- at or below `botFleeHealth` health (config, default 6 of 20) with a hostile mob within 8 blocks: flee home when the bot is in its home's dimension and home lies further from the mob than the bot, and away from the mob otherwise, with its own pathfinder as `goto` does, digging or pillaring out where it must, planning again when the mob moves more than 4 blocks and waiting a second after finding no way out; a fleeing bot does not chase a mob, but strikes the one it flees while that one is in its reach (once a second, as in a fight), such as a mob climbing the ladders of a shaft right under it, which it cannot outrun; it does not stop on the ladders to fight such a mob, as both strike once a second and a bot at flee health loses that fight, so it keeps climbing and striking;
- a falling block (sand, gravel) over it: step aside, out from under it.
- standing where another bot's `build` puts a block, when that build asks it to make way: step aside, to the nearest block within 4 out of the build's spots (back to the middle of its own block when only its edge is in one), for as long as the build keeps asking and a second after, or until its hold (below) ends, whichever comes first, so a bot does not make way again on its way back. A bot running `idle.py` keeps off the build's spots, standing where it stepped to, while the building bot holds the job the build was called with (`job=`, as `colony.builder` passes the `build` job it took); a build called without one holds it while the building bot holds any `build` job off its colony's job board and else until ten seconds after its last `build` call, so it does not walk back home onto the site between calls, such as while the builder fetches what it missed.

The nearer danger comes first, in the order of the list. A reflex takes over only where it has a way out: a bot walled in by fire, or under water with no air within reach, is left to its script.

### Travelling between dimensions

A bot goes between the Overworld and the Nether through a nether portal, as a player does: `travel(portal)` walks into the portal, which takes the bot at once (mobs do not wait in a portal as players do), and the bot comes out of the portal on the other side, which the game finds or builds as for a player. The game keeps a mob that came through a portal from going back for 15 seconds, and for as long as it stands in a portal; `travel` lifts that, so a bot can go back at once. Other walks (`goto`, `go_home`, work, fleeing) never step into a nether portal or dig at its frame: they go round it, or find no way if it is the only one. The script goes on in the other dimension, inside `travel`, which then gives ok: the game makes the bot anew there from its save, and the new bot carries on with the same script, the running action and all (see Save and resume), also a script not from the script library. Bots find a portal with `find_nearest("nether_portal")`, within 32 blocks, or in `portals()`: a bot remembers both ends of each nether portal it goes through, by `travel` or by walking in, the block of the portal it went into and the one it came out on, each a `Pos` with its `dimension`. They are saved with the bot and kept when it dies. A portal remembered replaces one in the same dimension within 16 blocks along each axis (the same portal, or one beside it), so going back and forth through one portal keeps two entries, and a bot keeps the newest 8. It remembers portals only as it goes through them, and forgets one when `travel` finds it gone: once the block at the position given is loaded and is no nether portal, before or while the bot walks there, `travel` stops and fails with `no_portal`, and every portal remembered in the bot's dimension within 16 blocks of that position along each axis (the same portal) leaves `portals()`. A portal in a chunk not loaded yet is not forgotten until the walk loads it. A remembered portal may be far and in unloaded chunks: `travel` walks there as `goto` does, loading the chunks on its way (with `botsLoadChunks` on; `not_loaded` otherwise). The end portal is not supported, nor remembered. In another dimension than its home's, `go_home` in `lib/home.py`, and `idle.py` (which has its own copy, so a changed or broken `lib/home.py` does not change idle bots), goes through a nether portal when the home is in the Overworld or the Nether: the nearest within 32 blocks, or else the nearest it remembers in the dimension it is in (`portal_out()`), the next nearest when that one is gone (`no_portal`), and walks home from where it comes out:

```python
def portal_out():
    portal = find_nearest("nether_portal")
    if portal is None:
        for p in portals():
            if p.dimension == dimension() and (portal is None or pos().distance(p) < pos().distance(portal)):
                portal = p
    return portal

if dimension() != home_dimension():
    portal = portal_out()
    while portal is not None:
        result = travel(portal)
        if result.reason != "no_portal":
            break
        portal = portal_out()  # the gone one is forgotten: the next
    if portal is not None and result:
        goto(home())
```

When a reflex takes over, the running action ends with `interrupted`. While the reflex holds the bot the script does not run; once the danger is past, the script goes on with that result and decides what to do next. Thresholds are config options, not script options, in v1; 0 turns the drowning or the fleeing reflex off.

## Idle, home and death

- **Home.** Every bot has a home: where it was first spawned, changed with `set_home(pos)`.
- **Idle.** A bot without a script, or whose script ended, stopped or failed, runs `idle.py` from the default library: walk home, then wait there. It goes home as `go_home` from `lib/home.py` does, with its own copy of that code so a changed or broken `lib/home.py` leaves idle bots alone: from another dimension than its home's through a nether portal within 32 blocks, or else the nearest one it remembers going through in that dimension, the next one when that is gone; without any it waits where it is (see Travelling between dimensions). This holds for every bot, a colony member as much as any other, and for a bot loaded from a world saved before milestone 4 with colony work under way: what it was doing is forgotten and it runs `idle.py`. Players can replace it with their own `idle.py` in a script folder, like any other default script. When `idle.py` itself ends or fails, it is not started again. An idle bot keeps its chunk loaded only while it walks (see Chunk loading).
- **Death.** A bot that dies drops its items where it died, like a player (its inventory and what it holds or wears), and its log and its owner's chat get a line saying where and how it died. It respawns at its home, in the dimension of its home (also when it died in another), after `botRespawnTicks` (config, default 200, 10 seconds), with full health. It keeps its UUID, name, skin, owner, home, log, `memory`, the portals it remembers and its colony (it stays a member while it waits; with `botRespawn` off, a bot that dies leaves its colony, and so does one whose wait is dropped without it coming back, giving back the jobs it took), and starts its last script again from the top with the same arguments: a library script as the library has it then, and `idle.py` when it was idle or its script is no longer in the library. `botRespawn = false` (config) makes death final instead. A script can tell it respawned from `memory`, or from `deaths()`, how often the bot has died, and go back for its items with `last_death()`, where it last died (saved, kept across respawns). The wait is saved with the world, so a bot waiting to respawn when the server stops still comes back. When the time is up and the chunk of its home is not loaded, the bot loads it with a short chunk ticket (with `botsLoadChunks` on; otherwise it waits until a player loads it).

## Chunk loading

A bot running a script keeps the chunk it stands in loaded, with a small chunk ticket that moves with it, so it keeps working when its owner walks away. The ticket makes the bot's chunk and the eight around it tick entities (a mob that steps into a chunk that does not tick entities stops for good), and loads the two rings past them. `goto` loads the chunks its path needs one stretch at a time, just before the bot enters them: the chunks within three of the straight way towards the target, 48 blocks ahead, and waits until they are loaded before it plans on (giving up with `not_loaded` after a minute). `botsLoadChunks = false` (config) turns both off: bots then stop when their chunk unloads, and `goto` fails with `not_loaded` when a chunk the straight way towards the target crosses in the next 48 blocks is not loaded.

A bot running the `idle.py` it fell back to (it has no script of its own, or that script ended, stopped or failed) holds its own ticket only while it walks: its `goto` home loads chunks as any other, but once it waits at home it lets its chunk go, so it stays loaded only while players keep it so. When the chunk unloads, the bot is saved with it and stops; when it loads again the bot carries on. Any other script, also one named `idle.py` run with `bot run`, keeps its chunk loaded all the time it runs.

`goto` may load chunks that were never generated, which generates them, as a player walking there would.

## Config

| Option | Default | Notes |
|---|---|---|
| `scriptInstructionsPerTick` | 2000 | Per bot. Capped at 20000; a higher value is lowered to the cap with a warning in the server log. The config comment says why: every bot may use its whole budget every tick, so 30 bots at 20000 are 600000 instructions a tick on the server thread, which can lag the game. Raise it only for scripts that think a lot between actions, and measure with `/antcraft profile`. |
| `scriptChatLevel` | `error` | Lowest log level shown in the owner's chat. `print` always shows. |
| `botRespawn` | `true` | Respawn dead bots at home. |
| `botRespawnTicks` | 200 | Delay before respawning. |
| `botFleeHealth` | 6 | Health (of 20) at or below which a bot flees a hostile mob near it (Reflexes); 0 never. |
| `botSurfaceAir` | 100 | Air (of 300 ticks) below which a bot under water swims up to breathe (Reflexes); 0 never. |
| `botsLoadChunks` | `true` | Running bots keep their chunk loaded, and `goto` loads chunks on its path; idle bots (running their fallback `idle.py`) only while they walk. |

## Files and commands

Scripts are loaded from two folders; the world folder wins on a name clash:

- `config/antcraft/scripts/` for every world (share scripts by copying files here);
- `<world>/antcraft/scripts/` for one world.

The mod copies its default scripts (`idle.py`, the library modules `lib/home.py`, `lib/shaft.py` and `lib/mine.py` and the colony scripts under `colony/`) into `config/antcraft/scripts/` each time the server starts, and never overwrites a file the player changed: `config/antcraft/scripts/.antcraft-defaults` records what it last copied, and a file that differs from that is the player's. A deleted default comes back.

Commands (single-player first; on a server they stay operator-only):

```
/antcraft script list                       scripts found, with compile errors
/antcraft script reload                     reload changed files; a bot whose script or imported module changed restarts it
/antcraft bot [bots] run <script> [args]    start a script on a bot (args are given to it as the list ARGS)
/antcraft bot [bots] stop
/antcraft bot [bots] status                 script, line, current action, last result
/antcraft bot [bots] log [n] [level]        last n log lines at or above level (default debug)
/antcraft core script <script> [args]       the Bot Core in your main hand makes bots that run this script
/antcraft core script clear                 ...and back to idle.py
```

`[bots]` is an entity selector; without it, the command acts on the nearest bot. A script is named by its path in its folder without `.py`, with dots for folders (`lib/trees.py` is `lib.trees`). A bot's owner, who gets its chat, is the player who placed its Bot Core, or who last started a script on it.

A Bot Core can carry a script and its args (the item's `antcraft:script` component, named in its tooltip), so you hand out workers rather than commands: the bot it makes starts that script the moment it is activated, owned by the player who used the core, as `bot run` would start it. A core without one makes a bot that runs `idle.py`. `core script` refuses a script that does not exist or does not compile, with its error; one gone or broken since the core was set is refused the same way when the core is used, in the player's chat, and the bot runs `idle.py`. Cores with different scripts do not stack. Writing scripts in a book and quill is out of scope for v1.

## Example scripts

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

A miner with a library function (`lib.shaft`, see Modules):

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

## What stays and what goes

**Stays (the engine behind actions):** `AntBot`, `PathFinder`, `PathFollower`, `PathBudget`, `CachedWorldView`, `LevelWorldView`, `LoadedBlocks`, `BlockBreaker`, `LineOfSight`, `BreakTime`, `Crafting`, `GameRecipeBook`, `Fuels`, `ToolKind`, `PickaxeTier`, `Tools`, `Storage` (moving items between containers, and `Storage.craft`, which `craft` uses), `TaskPlanner` (later behind a library `make(item)` helper), `Mineshaft`, `OreVein` and `Mining` (for the mining library of milestone 6), `BuildPlan` and `RegionPlan` (for `build` in milestone 7), the blueprint library, picker and preview ghost, `TickProfiler`, the config gate, `BotSkins`, the Bot Core item.

**Went in milestone 4:** the goals in `entity/goal` (`ColonyWorkGoal`, `WorkerGoal`, `LumberjackGoal`, `MinerGoal`, `FarmerGoal`, `BuilderGoal`, `HaulerGoal`, `CrafterGoal`, `GeneratorGoal`, `ReturnHomeGoal`, `ScaffoldCleanup`, `WalkToTargetGoal` with `Digging`), and from `colony` the job board, job scoring, stock planning and stock targets, orders, projects, tool upgrades, the territory and the survey, with the logic only they used (tree scanning and replanting, fields and farming, the quarry, terraforming, storage areas, ore revisits, lava sources and fire spread). Placing a blueprint for a colony to build went with them: the Blueprint item picks and previews a blueprint, and building comes back as an action. So did the colony screen, the build ghosts, `/antcraft goto` (a script's `goto` does it) and the colony commands but `colony info`, `colony radius` and `colony trust`/`untrust`. Their game tests went too. What remains of `Colony` is its core, members, radius, owner and trusted players; a colony saved with jobs, stock, orders, projects, builds or a mineshaft loads with those left out. Milestone 7 decides the rest.

## Tests

- **Language:** unit tests per feature: tokenizer, parser, compiler and VM, including the budget and save/resume (run a script half way, save, load, finish, compare with one uninterrupted run). Each unsupported feature has a test for its error message.
- **API:** one game test per action and per reason code it can return, each driven by a short script string run on a bot in the 8x8x8 test area, for example `assert mine((1, 1, 1))` followed by a block check.
- **Library:** acceptance game tests for default scripts: tree to wooden pickaxe, wood to stone pickaxe and furnace and charcoal, and digging a ladder shaft down to a set level and back.

Script-driven game tests also make new game tests shorter to write than today's.

## Milestones

Each milestone becomes one or more issues.

1. **Language core.** Tokenizer, parser, compiler and VM for expressions, variables, `if`/`while`/`for`, functions, lists, dicts and builtins. Pure unit tests; no game yet.
2. **Scripts in the game.** Script folders, `script list`/`reload`, `bot run`/`stop`/`status`/`log`, the budget and its config cap, output and logging levels, `say`, `wait`, `pos`, `home`/`set_home`, `goto` with chunk loading, and `idle.py`. A bot walks where a script tells it and goes home when done. README roadmap rewritten for the new direction.
3. **Core actions and senses.** `mine`, `place`, `pick_up`, `equip`, `inventory`/`count`/`has`, `block`, `find_nearest`, `scan`, `put`/`take`, `craft`, `smelt`, the `Result` reasons, `memory`. Acceptance: punch a tree to a wooden pickaxe as a script.
4. **Remove the goal-driven colony** (see What stays and what goes). Every bot without a script runs `idle.py`; the benchmark runs 30 bots on a script. (Done.)
5. **Reflexes, save/resume, imports, death.** Bots survive a reload mid-script; `import` and the default `lib/` folder; respawning at home. Done.
6. **Mining library.** `lib/shaft.py` and friends. Acceptance: the mineshaft, dug and laddered, every time. Started: the senses `mineshaft` and `mineshaft_at` (see Senses), and `lib/shaft.py` (see Modules), which digs a shaft to two levels, shuts it with a trapdoor and climbs out in eleven game tests, ten of them past seeded pockets of gravel, air and water beside the column, and goes on after a save half-way down or once given the ladders it lacked, past a pond beside the hatch, lava flowing into the column and a husk in the shaft; the sense `ore_vein` and `lib/mine.py`, which mines a level's hall and branches and the ore veins they uncover into the landing chest, and seals water and lava it meets; and `colony.miner`, with which a colony mines through one shared shaft.
7. **Colony again.** Shared colony memory and a script-level job board, so several bots split work by role; Bot Core items that carry a script; `build` as an action. Default colony scripts that do what the old colony did. Done: `colony()` and `colony_memory` (a member in another dimension than its core stays a member), the job board (see Jobs), Bot Cores that carry a script, `build`, and the colony scripts (see Colony scripts), `colony.miner` with milestone 6.
8. **Later.** Multiplayer permissions and sandbox limits per player, `try`/`except` and classes if scripts need them, an in-game script viewer or editor, sharing scripts through data packs, Litematica import.

## Open questions

- Answered: the instruction budget default (2000 per bot per tick) and cap (20000), the block search budget and the charges of searches and plans were first guesses; the bot benchmark measured them (30 bots, #322) and they stay. A tight loop runs about 9 ns an instruction: 30 bots at the default 2000 average 1.0 ms a tick, at the cap of 20000 (600,000 instructions a tick) 5.3 ms, worst ticks around 10 ms. The block search budget of 32768 blocks a tick, all of it used by 30 bots running `find_nearest` or `scan` of radius 32 over and over, averages 1.9 and 2.3 ms; a block read costs about 50 ns, some 6 instructions, so the charge of one instruction per 8 blocks is light, but the shared budget, not the charge, bounds what searches cost the server, and a charge at cost would hold a script up for seconds after each search of radius 32. The same goes for `reachable`: a node costs some 3 µs, a few hundred instructions, and 30 bots planning without end use the whole path budget of 2000 nodes a tick for about 7 ms a tick (worst ticks up to 38 ms); the charge stays at one instruction a node. A busy mix (a loop of 200, a scan and a `find_nearest` of radius 32, `goto`, `craft`) averages 2.6 ms and 30 bots felling trees and mining 2.2 ms. The costly case is a loop of natives that do their work at once: `craft` and `drop` with nothing left to craft average about 19 ms a tick for 30 bots, as each call counted one instruction but costs a microsecond or more. Those natives now count about their time in instructions (see Budget, #328): the craft case dropped from 12.8 to 1.6 ms a tick, and a loop of every such sense from 18.9 to 2.1 ms. Caching parsed selectors (#340) halved what `count` and `has` take, from about 0.65 to 0.3 µs a call (30 bots looping on both without a cost: 8.5 to 9.3 ms a tick before, 4.4 to 5.7 ms after), so they cost 20 instead of 50; the benchmark's count case, a loop of both, averages 2.5 ms with that cost. Re-measured on an idle machine at b75e9cd (#342, two runs): loop 0.94 and 1.12 ms, loop at the cap 4.7 and 4.8, craft 1.3, count 1.3, busy 3.1 and work 2.9 ms. Busy and work are above the 2.6 and 2.2 ms above, but 74fbbb4, measured the same day on the same machine, gives busy 3.0 and work 2.7 (craft 1.75), so the gap is the machine, not a regression since #328. The other natives that take a selector were re-measured with a case each (#343), from their time a call without a cost in two runs: `equip` about 0.3 µs, `drop` 0.45, `items_near` 0.17, `entities` (29 bots in range) 2.1, `craft` with nothing to craft 0.8 and `smelt` with no furnace in reach 26 µs. So `equip` costs 15 instead of 50, `drop` 20 instead of 40, `craft` 40 instead of 60 and `smelt` 1200 instead of 400, which it had outgrown: 30 bots failing to smelt averaged 4.3 ms a tick at 400 and 244 ms without a cost. `items_near` and `entities` were within 25% and stay. With the new costs each of these cases averages 1.3 to 1.6 ms, as the count case does. A `smelt` that fails at once spent its time reading every block within reach for a furnace and the bot's inventory once for each fuel item; it now skips the block search when no chunk section within reach has a furnace, blast furnace or smoker that cooks the item in its palette, and looks for fuel only once it found a furnace (#347). 30 bots failing to smelt without a cost went from 231 to 10 ms a tick (script time 230.8 to 10.0 ms, some 31 to 1.3 µs a call), so `smelt` costs 60 instead of 1200; the case averages 2.0 ms with that cost.
- Answered (#380): the milestone 6 and 7 natives were measured with a benchmark case each (30 bots, 600 ticks, the time a call timed around each native in a run of its own). Their time a call, and the cost set from it at 20 ns an instruction: `colony` (30 members) 0.7 µs, 10 to 35; `jobs` with some 120 jobs on the board 17 µs, about 0.15 µs a job, so 10 and 8 a job listed; `take_job` 0.65 µs, 10 to 30; `drop_job` 0.4 µs, 10 to 20, and `finish_job`, which does the same, with it; `blueprint(id)` (the warehouse, with and without `at`) 2 µs, 30 to 100; `matches` 0.3 µs, 1 to 15; `free_slots` 0.5 µs, stays 20; `ore_vein` of a small vein 0.3 µs, gets 10 on top of its block charge; `mineshaft_at` of two levels 30 to 36 µs, 30 to 1500. `post_job` was measured later (#394), the jobs case posting and cancelling a job each round: 0.7 µs a call, 10 to 35 (`finish_job` cancelling it about 0.55 µs, so it stays 20); the jobs case averages 1.59 / 1.45 ms. Average ms a tick of each case before / after: colony 3.4 / 1.6, jobs 25.2 / 1.4, blueprint 3.7 / 1.6, matches (with `free_slots`) 2.7 / 1.7, ore_vein 3.6 / 1.8, mineshaft_at 34.4 / 1.8. `mineshaft` read some 900 blocks a call where the first spot fits (61 µs, some 70 ns a block), and some 325,000 where none does (a case whose last level is in the bedrock: 12 ms a call, 180 ms on the tick all 30 bots started one). It now shares the block search budget and goes on over ticks as `find_nearest` does: the cases average 33.9 / 3.3 and 17.8 / 1.7 ms, worst ticks 46 / 5 and 180 / 4 ms. A spot once begun is read whole, so a tick can read up to one spot past the budget; re-measured (#394, two runs) the worst ticks are 5.05 and 5.34 ms where the first spot fits and 2.86 and 3.14 ms where none does, under 5 ms in the worst case, so spots were not split then. Since #398 the search pauses inside a spot too, before any block it checks, so a tick reads at most a block's checks (or a column's ground) past the budget: in two runs the worst ticks were 4.53 and 6.52 ms where the first spot fits (averages 3.12 and 3.10 ms) and 3.21 and 3.33 ms where none does (average 1.62 ms). The worst ticks are noise: in the second run `ore_vein` and `mineshaft_at`, which read no more than before, reached 3.89 and 4.54 ms. The first-spot-fits case's average stays about 1.5 ms over the no-fit case's, which reads as much: each search there ends in a site, about 36 a tick at some 900 blocks each, and turning each into its dict (its plan, as `mineshaft_at` does, 30 to 36 µs) was not counted against the block search budget. Since #399 it counts 700 blocks of it (about the time of 33 µs at some 50 ns a block), and a search that finds its site with less left waits for the next tick, so fewer searches end in a tick. The benchmark now also logs the p99 tick. In two runs: `mineshaft` averages 1.97 and 1.88 ms (p99 4.52 and 2.60, worst 4.95 and 3.90), `mineshaft none` 1.93 and 1.75 ms (p99 4.07 and 3.14, worst 4.29 and 3.53), within 0.13 ms of each other. `build` planning the warehouse takes some 13 µs a call (30 bots, one call a tick each, as it fails for missing ingredients at once): planning stays in one tick.
- Answered (#401): respawning costs nothing more. Losing all items, dropped where the bot died, is the cost; `last_death()` lets a script go back for them.
