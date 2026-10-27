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
 default script library        lib/trees.py, lib/shaft.py, later colony/*.py
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
- **Statements:** assignment (including `+=` and friends, and tuple unpacking `a, b = ...`), `if`/`elif`/`else`, `while`, `for x in ...`, `break`, `continue`, `pass`, `def` with default and keyword arguments, `return`, `global`, `import name`, `from name import a, b`.
- **Expressions:** arithmetic (`+ - * / // % **`, unary `-`), comparisons (chained `a < b < c` too), `and`/`or`/`not`, `in`/`not in`, `is`/`is not` (only for `None`), indexing and slicing, attribute access, calls, list and dict literals, conditional expressions (`a if c else b`), f-strings, list comprehensions.
- **Builtins:** `print`, `debug`, `info`, `warn`, `error` (see Output and logging), `len`, `range`, `min`, `max`, `abs`, `round`, `sum`, `sorted`, `reversed`, `enumerate`, `zip`, `str`, `int`, `float`, `bool`, `list`, `dict`, `isinstance` (for the builtin types), `any`, `all`.
- **Methods:** the common ones of `str` (`split`, `join`, `strip`, `startswith`, `endswith`, `replace`, `lower`, `upper`, `format`), `list` (`append`, `extend`, `pop`, `insert`, `remove`, `index`, `sort`), and `dict` (`get`, `keys`, `values`, `items`, `pop`, `setdefault`).
- **Truthiness** as in Python: `0`, `0.0`, `""`, `[]`, `{}`, `None`, `False` and failed `Result`s are false.

### Not supported (v1)

`class`, `try`/`except`/`raise`, `lambda`, `with`, generators and `yield`, decorators, `async`, set literals and `set`, `*args`/`**kwargs`, nested functions that capture variables (closures), `nonlocal`, the Python standard library. Several may come later. Most of them are needed less than in normal Python, because actions return results instead of raising.

### Errors

A compile error is reported when the script is loaded: `miner.py:12:5: expected ':' after 'if' condition`. A runtime error (`None` has no attribute `x`, index out of range, unknown function) stops the script, is logged at level `error` (so it shows in chat and in the bot log, see Output and logging), and the bot falls back to `idle.py`. Messages name the line and, where possible, hint at the cause: `miner.py:18: chest is None (find_nearest found nothing?)`.

### Output and logging

Every bot keeps a log (`/antcraft bot <name> log`). Lines have a level: `debug`, `info`, `warn` or `error`. Each line goes to the log, and to the chat of the bot's owner when its level is at or above `scriptChatLevel` (config, default `error`).

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

### Modules

`import trees` loads `trees.py` from the script folders (see Files), runs its top level once per bot and binds its names under `trees`. Default library modules live under `lib/` and are imported as `import lib.trees`. Circular imports are an error.

## Runtime

### Pipeline

Source is tokenized (with Python's `INDENT`/`DEDENT` rules), parsed into an AST, checked against the subset, and compiled to a compact bytecode per function. A stack VM with explicit frames runs the bytecode. None of this needs a world, so the whole language is unit tested with plain JUnit.

### Budget

Each bot gets `scriptInstructionsPerTick` (config, default 2000) VM instructions a tick. A script that uses them up simply continues on the next tick, so `while True: pass` costs a busy bot, not a frozen server. See Config for its limit. Senses that scan the world (`scan`, `find_nearest`) count extra instructions in proportion to the blocks they read, and share a server-wide budget the way pathfinding shares `PathBudget`.

### Pausing

A script pauses when it calls an action (until the action ends), calls `wait(ticks)`, or runs out of budget. The action's `Result` is the call's return value when the script continues.

### Save and resume

Because the VM's state is plain data (frames, operand stacks, globals, the call into the running action), a bot saves its running script with the world and resumes on load at the same instruction. The save holds a hash of each source file. If a file changed since, the script restarts from the top instead, keeping `memory`. Values that refer to the world (`Entity`) are saved by UUID and may come back as no longer valid.

## The bot API

Names are `snake_case`. The API is available as builtins in every script (no import needed). Positions are `Pos` values; anything that takes a position also takes a tuple `(x, y, z)`.

### Types

- **`Pos`**: `x`, `y`, `z`; `+` and `-` with another `Pos` or tuple; `up(n=1)`, `down`, `north`, `south`, `east`, `west`; `distance(other)`. Usable as a dict key.
- **`Block`**: `id` (`"minecraft:oak_log"`), `pos`, `is_air`, `is_fluid`, `tags` (list).
- **`Stack`**: `item`, `count`.
- **`Entity`**: `id` (UUID string), `type`, `pos`, `health`, `valid` (false once it is gone or out of range).
- **`Result`**: returned by every action. Truthy when the action succeeded. `ok` (bool), `reason` (a string code, `None` on success), `value` (what the action produced, if anything, for example how many items `craft` made).

### Selectors

Where an item or block is asked for, a string selects it: `"oak_log"` (namespace `minecraft:` assumed), `"minecraft:oak_log"`, a tag `"#minecraft:logs"` or `"#c:ores/iron"`, or a list of these.

### Senses (instant, do not pause)

| Function | Returns |
|---|---|
| `me()` | the bot's own `Entity` |
| `pos()` | the bot's position |
| `health()`, `max_health()` | numbers |
| `inventory()` | list of `Stack` |
| `count(item)` | how many matching items the bot carries |
| `has(item, n=1)` | `count(item) >= n` |
| `held()` | the `Stack` in its hand or `None` |
| `block(pos)` | `Block` (unloaded positions give `None`) |
| `find_nearest(what, radius=32)` | nearest matching block's `Pos` the bot can see from its loaded surroundings, or `None` |
| `scan(what, radius=16, limit=64)` | list of matching `Pos`, nearest first |
| `entities(what=None, radius=16)` | list of `Entity` nearest first; `what` is an entity type or tag |
| `items_near(item=None, radius=16)` | list of dropped item `Entity` |
| `container(pos)` | list of `Stack` in a chest, barrel or furnace, or `None` |
| `time()`, `is_day()` | world time of day |
| `home()` | the bot's home `Pos`: where it was first spawned, until `set_home` changes it |
| `set_home(pos)` | sets the home (saved with the bot); returns nothing |
| `deaths()` | how often the bot has died |
| `reachable(pos)` | whether a path exists (costs pathfinding budget, may pause) |

### Actions (take ticks, return `Result`)

| Function | Does |
|---|---|
| `goto(pos, near=0)` | walk to within `near` blocks, digging, pillaring and bridging as the pathfinder allows. When the next stretch of the path enters an unloaded chunk, the bot loads it (see Chunk loading) and plans on; if it cannot be loaded, `goto` stops with `not_loaded` |
| `mine(pos)` | walk into reach and break the block with the best tool carried; drops are picked up |
| `place(pos, item, facing=None)` | walk into reach and place a block |
| `pick_up(what=None, radius=8)` | collect dropped items |
| `equip(item)` | hold an item |
| `use(pos, item=None)` | use the held or given item on a block (bucket, hoe, bone meal, flint and steel) |
| `attack(entity)` | fight until the target is dead, gone, or the bot must flee |
| `put(container, item, n=None)` | put items into a container (all matching when `n` is `None`) |
| `take(container, item, n=None)` | take items out |
| `craft(item, n=1, table=None)` | craft by one recipe from carried items; 3x3 recipes need a crafting table at `table` or within reach |
| `smelt(item, n=1, furnace=None, fuel=None)` | load a furnace, wait, take the output |
| `drop(item, n=None)` | drop items |
| `wait(ticks)` | do nothing |
| `say(text)` | show text above the bot and in its owner's chat |
| `stop()` | end the script |

Later milestones add `build(blueprint, at, turn=0)` (the existing build-plan code as one action) and `till`, `plant` and `harvest` helpers.

### Reason codes

`unreachable`, `no_path_budget` (only seen if a script asks again in the same tick), `out_of_reach`, `not_loaded`, `no_tool` (needs a better tool), `no_item`, `no_space` (inventory or container full), `no_recipe`, `missing_ingredients`, `no_table`, `no_furnace`, `blocked` (something is in the way), `protected` (the block may not be changed, for example by claim rules), `target_gone`, `interrupted` (a reflex took over), `stopped`, `timeout`.

### Memory

`memory` is a dict that belongs to the bot and is saved with it. It survives restarts and script edits. Values must be plain data: numbers, strings, booleans, `None`, `Pos`, and lists, tuples and dicts of these.

```python
if "chest" not in memory:
    memory["chest"] = find_nearest("chest")
```

## Reflexes

Reflexes run every tick in Java, before the script, and cannot be turned off by a script:

- the pathfinder never plans through lava, fire, or a fall that hurts (as today);
- stepping next to flowing lava or into fire: back away;
- under water and short of air: swim up;
- low health with a hostile mob near: flee towards the bot's home or away from the mob;
- a block about to fall on the bot (sand, gravel): step aside.

When a reflex takes over, the running action ends with `interrupted` and the script decides what to do next. Thresholds (flee health, for example) are config options, not script options, in v1.

## Idle, home and death

- **Home.** Every bot has a home: where it was first spawned, changed with `set_home(pos)`.
- **Idle.** A bot without a script, or whose script ended, stopped or failed, runs `idle.py` from the default library: walk home, then wait there. Players can replace it with their own `idle.py` in a script folder, like any other default script.
- **Death.** A bot that dies drops its items where it died, like a player, and respawns at its home after `botRespawnTicks` (config, default 200, 10 seconds). It keeps its name, skin, home and `memory`, and starts its last script again from the top. `botRespawn = false` (config) makes death final instead. A script can tell it respawned from `memory`, or from `deaths()`, how often the bot has died.

## Chunk loading

A bot running a script keeps the chunk it stands in loaded, with a small chunk ticket that moves with it, so it keeps working when its owner walks away. `goto` loads the chunks its path needs one stretch at a time, just before the bot enters them. `botsLoadChunks = false` (config) turns both off: bots then stop when their chunk unloads, and `goto` fails with `not_loaded` at the edge of loaded terrain.

`goto` may load chunks that were never generated, which generates them, as a player walking there would.

## Config

| Option | Default | Notes |
|---|---|---|
| `scriptInstructionsPerTick` | 2000 | Per bot. Capped at 20000; a higher value is lowered to the cap with a warning in the server log. The config comment says why: every bot may use its whole budget every tick, so 30 bots at 20000 are 600000 instructions a tick on the server thread, which can lag the game. Raise it only for scripts that think a lot between actions, and measure with `/antcraft profile`. |
| `scriptChatLevel` | `error` | Lowest log level shown in the owner's chat. `print` always shows. |
| `botRespawn` | `true` | Respawn dead bots at home. |
| `botRespawnTicks` | 200 | Delay before respawning. |
| `botsLoadChunks` | `true` | Running bots keep their chunk loaded, and `goto` loads chunks on its path. |

## Files and commands

Scripts are loaded from two folders; the world folder wins on a name clash:

- `config/antcraft/scripts/` for every world (share scripts by copying files here);
- `<world>/antcraft/scripts/` for one world.

The mod copies its default library into `config/antcraft/scripts/lib/` on first start and on update, and never overwrites a file the player changed.

Commands (single-player first; on a server they stay operator-only):

```
/antcraft script list                       scripts found, with compile errors
/antcraft script reload                     reload changed files; running bots restart their script
/antcraft bot <name> run <script> [args]    start a script on a bot (args are given to it as the list ARGS)
/antcraft bot <name> stop
/antcraft bot <name> status                 script, line, current action, last result
/antcraft bot <name> log [n] [level]        last n log lines at or above level (default debug)
```

A Bot Core item can carry a script name, so a new bot starts running it when placed. Writing scripts in a book and quill is out of scope for v1.

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
craft("oak_planks", 3)
craft("stick", 1)
table_spot = pos().north()
craft("crafting_table") and place(table_spot, "crafting_table")
r = craft("wooden_pickaxe", table=table_spot)
say("pickaxe!" if r else f"no pickaxe: {r.reason}")
```

A miner with a library function:

```python
import lib.shaft

chest = memory.get("chest") or find_nearest("chest")
memory["chest"] = chest
while True:
    r = lib.shaft.dig_down(to_y=16, ladders=True)
    if not r:
        say(f"shaft stopped: {r.reason}")
        break
    for ore in scan("#c:ores/iron", radius=8):
        mine(ore)
    goto(chest, near=2)
    put(chest, "#c:raw_materials")
```

## What stays and what goes

**Stays (becomes the engine behind actions):** `AntBot`, `PathFinder`, `PathFollower`, `PathBudget`, `CachedWorldView`, `LevelWorldView`, `LoadedBlocks`, `BlockBreaker`, `LineOfSight`, `BreakTime`, `Crafting`, `GameRecipeBook`, `Fuels`, `ToolKind`, `PickaxeTier`, `Tools`, `TaskPlanner` (later behind a library `make(item)` helper), the blueprint code and ghosts, `TickProfiler`, the config gate, `BotSkins`, the Bot Core item.

**Goes, once the actions in milestone 3 work:** the goals in `entity/goal` (`ColonyWorkGoal`, `WorkerGoal`, `LumberjackGoal`, `MinerGoal`, `FarmerGoal`, `BuilderGoal`, `HaulerGoal`, `CrafterGoal`, `GeneratorGoal`, `ReturnHomeGoal`, `ScaffoldCleanup`), and from `colony` the job board, job scoring, stock planning, projects and the survey. Their game tests go with them. Pieces of their logic that an action needs (for example `Digging`, `OreVein`, `Mineshaft`) are moved into the engine first, not deleted. What remains of `Colony` and the colony core block is decided in milestone 7.

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
4. **Remove the goal-driven colony** (see What stays and what goes).
5. **Reflexes, save/resume, imports, death.** Bots survive a reload mid-script; `import` and the default `lib/` folder; respawning at home.
6. **Mining library.** `lib/shaft.py` and friends. Acceptance: the mineshaft, dug and laddered, every time.
7. **Colony again.** Shared colony memory and a script-level job board, so several bots split work by role; Bot Core items that carry a script; `build` as an action. Default colony scripts that do what the old colony did.
8. **Later.** Multiplayer permissions and sandbox limits per player, `try`/`except` and classes if scripts need them, an in-game script viewer or editor, sharing scripts through data packs, Litematica import.

## Open questions

- The instruction budget default (2000 per bot per tick) and cap (20000) are first guesses; the benchmark should confirm them.
- Should respawning cost something later, such as materials from the colony, once colonies return?
