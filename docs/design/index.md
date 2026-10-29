# Design

Written 2026-10-27. This replaces the goal-driven colony as the direction of the mod; the roadmap below follows the milestones.

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

## Runtime pipeline

Source is tokenized (with Python's `INDENT`/`DEDENT` rules), parsed into an AST, checked against the subset, and compiled to a compact bytecode per function. A stack VM with explicit frames runs the bytecode. None of this needs a world, so the whole language is unit tested with plain JUnit. Runtime errors mostly use CPython's messages (`list index out of range`, `'NoneType' object has no attribute 'pos'`) after the file and line, and calls nest at most 200 deep.

## Roadmap

The mod has turned from goal-driven colonies into **scripted bots**: the mod gives bots robust actions and senses, and players write their behaviour in a small subset of Python. The colony comes back later as a library of default scripts. The milestones are below.

| Milestone | Goal |
|-----------|------|
| 1 | Language core: tokenizer, parser, compiler and a pausable VM with the builtins (done) |
| 2 | Scripts in the game: script folders, `script list`/`reload`, `bot run`/`stop`/`status`/`log`, the instruction budget, output and logging, `say`, `wait`, `pos`, `home`/`set_home`, `goto` with chunk loading, and `idle.py` (done) |
| 3 | Core actions and senses: `mine`, `place`, `pick_up`, `craft`, `smelt`, `find_nearest`, `scan`, containers, `memory`; a tree punched into a wooden pickaxe by a script (done) |
| 4 | The goal-driven colony removed; its engine parts kept behind the actions; every bot without a script runs `idle.py` (done) |
| 5 | Reflexes, saving and resuming scripts, `import` and the default `lib/` folder, respawning at home, travel through nether portals (done) |
| 6 | Mining library: `lib/shaft.py`, the mineshaft, dug and laddered, every time (done: the senses `mineshaft(levels)` and `mineshaft_at(top, levels)` give a script where a ladder shaft goes and its ladders, landings, chests, halls, branches and torches, and `lib/shaft.py` digs it, past gravel, air and water, and climbs it; `ore_vein(pos)` and `lib/mine.py` mine its halls, branches and ore veins, sealing the fluid they meet; `colony.miner` mines with them through one shaft per colony) |
| 7 | Colony again: shared colony memory (`colony()`, `colony_memory`), a script-level job board (`post_job`, `take_job`), Bot Cores that carry a script, `build` as an action, and the default colony scripts `colony.lumberjack`, `colony.hauler`, `colony.builder`, `colony.farmer` and, with milestone 6, `colony.miner` (done) |
| 8 | Later: per-player limits, more of Python, an in-game script viewer, scripts in data packs, Litematica import |

Done before the change of direction: the bot entity, pathfinding that digs, pillars and bridges, the colony core and job board, the lumberjack, miner, farmer, hauler, guard and crafter work, blueprints with roles, the placement and build ghosts, and the warehouse, farm and cobblestone generator. Milestone 4 removed the job board, the work goals, stock planning, projects and placing blueprints for a colony; what the actions need of them (pathfinding, block breaking, crafting, the mineshaft and ore vein plans, build plans) stays.

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
4. **Remove the goal-driven colony** (see [What stays and what goes](#what-stays-and-what-goes)). Every bot without a script runs `idle.py`; the benchmark runs 30 bots on a script. (Done.)
5. **Reflexes, save/resume, imports, death.** Bots survive a reload mid-script; `import` and the default `lib/` folder; respawning at home. Done.
6. **Mining library.** `lib/shaft.py` and friends. Acceptance: the mineshaft, dug and laddered, every time. Done, with its game tests passing in the nightly runs: the senses `mineshaft` and `mineshaft_at` (see [Mining](../reference/index.md#mining)), and `lib/shaft.py` (see [Library modules](../reference/index.md#library-modules)), which digs a shaft to two levels, shuts it with a trapdoor and climbs out in eleven game tests, ten of them past seeded pockets of gravel, air and water beside the column, and goes on after a save half-way down or once given the ladders it lacked, past a pond beside the hatch, lava flowing into the column and a husk in the shaft; the sense `ore_vein` and `lib/mine.py`, which mines a level's hall and branches and the ore veins they uncover into the landing chest, and seals water and lava it meets; and `colony.miner`, with which a colony mines through one shared shaft.
7. **Colony again.** Shared colony memory and a script-level job board, so several bots split work by role; Bot Core items that carry a script; `build` as an action. Default colony scripts that do what the old colony did. Done: `colony()` and `colony_memory` (a member in another dimension than its core stays a member), the job board (see [Jobs](../features/colonies.md#jobs)), Bot Cores that carry a script, `build`, and the colony scripts (see [Colony scripts](../features/colony-scripts.md)), `colony.miner` with milestone 6.
8. **Later.** Multiplayer permissions and sandbox limits per player, `try`/`except` and classes if scripts need them, an in-game script viewer or editor, sharing scripts through data packs, Litematica import.

## Open questions

- Answered: the instruction budget default (2000 per bot per tick) and cap (20000), the block search budget and the charges of searches and plans were first guesses; the bot benchmark measured them (30 bots, #322) and they stay. A tight loop runs about 9 ns an instruction: 30 bots at the default 2000 average 1.0 ms a tick, at the cap of 20000 (600,000 instructions a tick) 5.3 ms, worst ticks around 10 ms. The block search budget of 32768 blocks a tick, all of it used by 30 bots running `find_nearest` or `scan` of radius 32 over and over, averages 1.9 and 2.3 ms; a block read costs about 50 ns, some 6 instructions, so the charge of one instruction per 8 blocks is light, but the shared budget, not the charge, bounds what searches cost the server, and a charge at cost would hold a script up for seconds after each search of radius 32. The same goes for `reachable`: a node costs some 3 µs, a few hundred instructions, and 30 bots planning without end use the whole path budget of 2000 nodes a tick for about 7 ms a tick (worst ticks up to 38 ms); the charge stays at one instruction a node. A busy mix (a loop of 200, a scan and a `find_nearest` of radius 32, `goto`, `craft`) averages 2.6 ms and 30 bots felling trees and mining 2.2 ms. The costly case is a loop of natives that do their work at once: `craft` and `drop` with nothing left to craft average about 19 ms a tick for 30 bots, as each call counted one instruction but costs a microsecond or more. Those natives now count about their time in instructions (see [Budget](../features/scripts.md#budget), #328): the craft case dropped from 12.8 to 1.6 ms a tick, and a loop of every such sense from 18.9 to 2.1 ms. Caching parsed selectors (#340) halved what `count` and `has` take, from about 0.65 to 0.3 µs a call (30 bots looping on both without a cost: 8.5 to 9.3 ms a tick before, 4.4 to 5.7 ms after), so they cost 20 instead of 50; the benchmark's count case, a loop of both, averages 2.5 ms with that cost. Re-measured on an idle machine at b75e9cd (#342, two runs): loop 0.94 and 1.12 ms, loop at the cap 4.7 and 4.8, craft 1.3, count 1.3, busy 3.1 and work 2.9 ms. Busy and work are above the 2.6 and 2.2 ms above, but 74fbbb4, measured the same day on the same machine, gives busy 3.0 and work 2.7 (craft 1.75), so the gap is the machine, not a regression since #328. The other natives that take a selector were re-measured with a case each (#343), from their time a call without a cost in two runs: `equip` about 0.3 µs, `drop` 0.45, `items_near` 0.17, `entities` (29 bots in range) 2.1, `craft` with nothing to craft 0.8 and `smelt` with no furnace in reach 26 µs. So `equip` costs 15 instead of 50, `drop` 20 instead of 40, `craft` 40 instead of 60 and `smelt` 1200 instead of 400, which it had outgrown: 30 bots failing to smelt averaged 4.3 ms a tick at 400 and 244 ms without a cost. `items_near` and `entities` were within 25% and stay. With the new costs each of these cases averages 1.3 to 1.6 ms, as the count case does. A `smelt` that fails at once spent its time reading every block within reach for a furnace and the bot's inventory once for each fuel item; it now skips the block search when no chunk section within reach has a furnace, blast furnace or smoker that cooks the item in its palette, and looks for fuel only once it found a furnace (#347). 30 bots failing to smelt without a cost went from 231 to 10 ms a tick (script time 230.8 to 10.0 ms, some 31 to 1.3 µs a call), so `smelt` costs 60 instead of 1200; the case averages 2.0 ms with that cost.
- Answered (#380): the milestone 6 and 7 natives were measured with a benchmark case each (30 bots, 600 ticks, the time a call timed around each native in a run of its own). Their time a call, and the cost set from it at 20 ns an instruction: `colony` (30 members) 0.7 µs, 10 to 35; `jobs` with some 120 jobs on the board 17 µs, about 0.15 µs a job, so 10 and 8 a job listed; `take_job` 0.65 µs, 10 to 30; `drop_job` 0.4 µs, 10 to 20, and `finish_job`, which does the same, with it; `blueprint(id)` (the warehouse, with and without `at`) 2 µs, 30 to 100; `matches` 0.3 µs, 1 to 15; `free_slots` 0.5 µs, stays 20; `ore_vein` of a small vein 0.3 µs, gets 10 on top of its block charge; `mineshaft_at` of two levels 30 to 36 µs, 30 to 1500. `post_job` was measured later (#394), the jobs case posting and cancelling a job each round: 0.7 µs a call, 10 to 35 (`finish_job` cancelling it about 0.55 µs, so it stays 20); the jobs case averages 1.59 / 1.45 ms. Average ms a tick of each case before / after: colony 3.4 / 1.6, jobs 25.2 / 1.4, blueprint 3.7 / 1.6, matches (with `free_slots`) 2.7 / 1.7, ore_vein 3.6 / 1.8, mineshaft_at 34.4 / 1.8. `mineshaft` read some 900 blocks a call where the first spot fits (61 µs, some 70 ns a block), and some 325,000 where none does (a case whose last level is in the bedrock: 12 ms a call, 180 ms on the tick all 30 bots started one). It now shares the block search budget and goes on over ticks as `find_nearest` does: the cases average 33.9 / 3.3 and 17.8 / 1.7 ms, worst ticks 46 / 5 and 180 / 4 ms. A spot once begun is read whole, so a tick can read up to one spot past the budget; re-measured (#394, two runs) the worst ticks are 5.05 and 5.34 ms where the first spot fits and 2.86 and 3.14 ms where none does, under 5 ms in the worst case, so spots were not split then. Since #398 the search pauses inside a spot too, before any block it checks, so a tick reads at most a block's checks (or a column's ground) past the budget: in two runs the worst ticks were 4.53 and 6.52 ms where the first spot fits (averages 3.12 and 3.10 ms) and 3.21 and 3.33 ms where none does (average 1.62 ms). The worst ticks are noise: in the second run `ore_vein` and `mineshaft_at`, which read no more than before, reached 3.89 and 4.54 ms. The first-spot-fits case's average stays about 1.5 ms over the no-fit case's, which reads as much: each search there ends in a site, about 36 a tick at some 900 blocks each, and turning each into its dict (its plan, as `mineshaft_at` does, 30 to 36 µs) was not counted against the block search budget. Since #399 it counts 700 blocks of it (about the time of 33 µs at some 50 ns a block), and a search that finds its site with less left waits for the next tick, so fewer searches end in a tick. The benchmark now also logs the p99 tick. In two runs: `mineshaft` averages 1.97 and 1.88 ms (p99 4.52 and 2.60, worst 4.95 and 3.90), `mineshaft none` 1.93 and 1.75 ms (p99 4.07 and 3.14, worst 4.29 and 3.53), within 0.13 ms of each other. `build` planning the warehouse takes some 13 µs a call (30 bots, one call a tick each, as it fails for missing ingredients at once): planning stays in one tick.
- Answered (#401): respawning costs nothing more. Losing all items, dropped where the bot died, is the cost; `last_death()` lets a script go back for them.
