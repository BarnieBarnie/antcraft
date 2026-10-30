# Development

A quick tour of the codebase for new contributors, and how to build and test the mod. For the development rules, read [CLAUDE.md](https://github.com/barniebarnie/antcraft/blob/main/CLAUDE.md).

## What it is

AntCraft is a Fabric mod for Minecraft 26.3 (Java 25, Mojang names). It adds bot entities (`AntBot`) that run scripts written in a small subset of Python: the script decides what a bot does, through actions such as `goto`, `mine` and `craft` and senses such as `find_nearest`. A bot without a script runs `idle.py` (walk home, wait). The design and milestones are in [Design](../design/index.md); the goal-driven colony it replaced was removed in milestone 4.

## Source sets

| Directory | Purpose |
|-----------|---------|
| `src/main/java` | Mod logic, runs on the server (about 13k lines) |
| `src/client/java` | Client only: the bot renderer, the blueprint picker and its preview ghost, the script editor |
| `src/test/java` | JUnit unit tests for pure logic |
| `src/gametest/java` | Fabric GameTests: real bots on a headless server |
| `src/main/resources` | `fabric.mod.json`, lang, textures, recipes, tags, loot tables |
| `tools/skins.py` | Generates the work skins under `textures/entity/bot/` (needs Pillow) |
| `scripts/check.sh` | Gradle wrapper that prints a short PASS/FAIL summary |

The main package is `io.github.barniebarnie.antcraft`.

## Package map

All paths are relative to `src/main/java/io/github/barniebarnie/antcraft`.

- **`AntCraft.java`**: the entrypoint. It loads the config and registers blocks, entities, items, commands, scripts, blueprints and networking. Start reading here.
- **`config/`**: `AntCraftConfig` reads `config/antcraft.properties` and holds the fair-play gate, `botsAllowed(server)`. Dedicated servers must opt in.
- **`registry/`**: `ModBlocks`, `ModEntities`, `ModItems`. Plain registration.
- **`block/`, `item/`**: `ColonyCoreBlock` and its block entity (54 slots), `BotCoreItem`, which activates a bot, and `BlueprintItem`, which picks a blueprint.
- **`entity/`**
  - `AntBot`: the mob itself, with a 27-slot inventory, home, owner, colony id, its script and log and the scripts' `memory`. Each tick it runs its script (or `idle.py`), before its few vanilla goals (float, fight, look around).
  - `BotBody` (what move actions do with a bot), `BotHands` (mining, placing, using, dropping and picking up, as a player would), `BotChunks` (chunk tickets), `Tools` (picks the best tool or weapon), `BotSkins`.
  - `navigation/`: `PathFollower` carries out paths step by step (dig, place scaffold, move, open doors); `BlockBreaker` breaks blocks over time like a player; `LineOfSight`.
- **`script/`**: the scripting runtime. `lang/` is the language (tokenizer, parser, compiler, pausable VM, builtins), pure and unit-tested. `ScriptRun` runs one script on a bot a tick at a time; `BotApi` registers the natives, split into `MoveApi`, `SenseApi`, `WorkApi` and `CraftApi`; `ScriptLibrary`, `ServerScripts` and `DefaultScripts` load the script folders and copy `idle.py`; `PlayerLimits` decides who may script which bot and shares out a player's instruction budget.
- **`pathing/`**: a custom A* (`PathFinder`) over the `WorldView` interface. `LevelWorldView` backs it with a live world; tests use `GridWorld`. `MoveType`, `Step`, `Path` and `Goal` describe moves and targets.
- **`colony/`**: `Colony` (core, members, radius, owner, trusted players; saved with a Codec that ignores the fields older versions wrote), `ColonyManager` (the `SavedData` holding a level's colonies), `ColonyActions` (who may change a colony), `BotActivation` (a Bot Core into a bot), and `TaskPlanner` (an order into crafting steps, generic over the item type so it is unit-tested without a game).
- **`work/`**: world logic and plain-data plans the actions build on or will: `BuildPlan` (ordered clear and place steps in batches; progress lives in the world), `RegionPlan` (structures into plans), `Mineshaft`, `OreVein`, `Mining`, `Storage` (moving items between containers, crafting from one), `Crafting`, `GameRecipeBook` (the real recipes behind `TaskPlanner`), `ToolKind`, `PickaxeTier`, `Fuels`.
- **`blueprint/`**: the blueprint library loaded from data packs (JSON blueprints, and Litematica schematics read by `Litematic`), and what the picker and preview ghost need of it.
- **`command/`**: `AntCraftCommands`, the whole `/antcraft` Brigadier tree.
- **`network/`**: `BlueprintNetworking`, the payload records between the blueprint picker and the server; `ScriptNetworking`, those of the in-game script editor, with the server's save handler (the checks of a saved name and source are in `script/ScriptEdits`).

## Runtime flow

1. A player uses a Bot Core: an `AntBot` spawns, owned by that player, and joins the nearest colony they may change, if any.
2. On its first tick the bot has no script, so it runs `idle.py`: it walks home, where it spawned, and waits.
3. `/antcraft bot run <script>` starts a script instead. Each tick `ScriptRun` resumes the current action (a walk, a dig) and, once it is done, runs the script on within its instruction budget until the next action pauses it.
4. When the script ends, fails or is stopped, the bot runs `idle.py` again.
5. Everything persists through Codecs in `Colony` and `AntBot` (`ValueInput` and `ValueOutput`); a running script is saved with its bot and resumed on load (see [Save and resume](../features/scripts.md#save-and-resume)).

## Workflow rules

These come from [CLAUDE.md](https://github.com/BarnieBarnie/antcraft/blob/main/CLAUDE.md).

- **Strict test-driven development**: write a failing test first (a compile error counts as red), make it green, then refactor. Never weaken a test to make it pass.
- **Choosing the test type**
  - Pure logic (planners, pathfinding, plans, scoring) gets a unit test. Keep that logic free of a world. If a test needs registries, call `SharedConstants.tryDetectVersion()` and `Bootstrap.bootStrap()` in `@BeforeAll`. `VanillaData` and `GridWorld` in the tests are useful helpers.
  - Bot behaviour gets a game test. Each test gets an empty 8x8x8 area with a stone floor at y=0. Use `helper.succeedWhen` and keep `maxTicks` tight. Copy the `CustomTestMethodInvoker` pattern from `AntBotGameTest`, which enables bots with `AntCraftConfig.setValues`.
  - Game tests run concurrently, so never toggle global config in one to test the opt-in gate. Test the gate with a unit test.
- `LangKeysTest` fails when code uses a `"...antcraft..."` lang key that is missing from `en_us.json`, so add new keys there.

## Commands

```sh
scripts/check.sh                       # full build, the same as CI
scripts/check.sh test                  # unit tests
scripts/check.sh runGameTest -PgameTest='antcraft-gametest:work_game_test*'
./gradlew runClient                    # play-test
```

Game test ids are `antcraft-gametest:<class_in_snake_case>_<method_in_snake_case>`. The full output goes to `build/check.log`. CI (`.github/workflows/build.yml`) runs `./gradlew build` on every push and pull request.

## Code conventions

- Tabs for indentation, `@Nullable` from jspecify, Mojang names (`ServerLevel`, `BlockPos`, `ItemStack`).
- Javadoc explains *why*, in plain prose. Match the surrounding comment density.
- `ponytail:` comments mark a known simplification or limitation. Grep for them to find refactoring ideas.
- Commit messages describe the behaviour in full sentences, without `feat:` prefixes. See `git log`.
- Save compatibility matters: when you change a Codec, keep the old shape loading with `Codec.withAlternative`.

## Automation in the repo

- `.claude/skills/new-issue` files GitHub issues.
- `.claude/skills/work-issues` and `.claude/agents/issue-worker.md` work through open issues in parallel git worktrees and merge the results.
- When you work alongside those agents, use your own branch to avoid conflicts.

## Good first places to start

- **Add a script action or sense**: follow `WorkApi` or `SenseApi`, with a game test per reason code it returns (see `WorkGameTest`).
- **The language**: a builtin or method scripts are missing, in `script/lang`, covered by unit tests, for fast feedback.
- **Small, isolated logic** in `work/` or `pathing/`, covered by unit tests.

## Building and testing

Requires JDK 25.

```sh
./gradlew runClient   # launch Minecraft with the mod
./gradlew test        # unit tests
./gradlew runGameTest # bot behaviour tests on a headless server
./gradlew build       # runs both test suites; jar in build/libs/
```

Development is test-first. See [CLAUDE.md](https://github.com/BarnieBarnie/antcraft/blob/main/CLAUDE.md) for the workflow.

The bot benchmark (`BenchmarkGameTest`) runs 30 bots in a 48x48 area through a series of cases, each a script every bot runs for 1200 ticks: a loop that only computes (at the default instruction budget and at the cap), `find_nearest` and `scan` of radius 32, `reachable` all over the area, `goto` back and forth, `craft` and `drop` in a loop, a busy mix of them, and last, for 3600 ticks, work: fell the nearest tree and pick up its logs, then mine the coal and iron ore it finds, then walk home and wait. For each case it logs the average and worst server tick, where the time went (as `/antcraft profile report` does), the script instructions run a tick and what the bots were at, then a summary line per case; it fails if a case's average tick reaches 50 ms. The layout and ores are fixed and the world is deleted first, so runs compare, though the bots' races for the same trees and the machine's load still vary from run to run. It is left out of every other game test run:

```sh
./gradlew runGameTest -Pbenchmark          # or: scripts/check.sh runGameTest -Pbenchmark
grep -A10 'Benchmark: summary' build/check.log   # the summary, when run through scripts/check.sh (-A30 'Benchmark:' for each case)
```

The game test server runs ticks back to back rather than 20 a second, so the 3600 ticks take well under a minute.

The work skins under `textures/entity/bot/` (one per kind of work the goal-driven colony had; bots wear a vanilla skin for now, see `BotSkins`) are drawn by `tools/skins.py` (needs Pillow). To add or tweak a skin, edit its entry in `SKINS` and regenerate them all:

```sh
python3 tools/skins.py src/main/resources/assets/antcraft/textures/entity/bot
```

Structures for blueprints go in `src/main/resources/data/antcraft/structure/` as `.nbt` files, saved with a structure block. In 26.3 a palette entry is `{id, properties}`; a file written in the old `{Name, Properties}` form loads as air.

Targets Minecraft 26.3 with Fabric Loader 0.19.5 and Fabric API.

## Architecture

- **Bot entity**: custom `PathfinderMob` rendered with a player model and skin. Cheap enough for many bots, and does not appear in the tab list.
- **Scripts**: each bot runs one script at a time on a pausable VM with a per-tick instruction budget (`script/lang`); actions such as `goto`, `mine` and `craft` take ticks and pause the script until they are done (`script/*Api`). The VM's state is plain data, saved with the bot and resumed on load. See [Scripts in the game](../features/scripts.md) and [Design](../design/index.md).
- **Reflexes**: Java, always on, before the script each tick (`entity/Reflexes`).
- **Navigation**: custom A* over block costs (walk, dig, place, climb, swim), followed by `PathFollower`, with a shared node budget (`PathBudget`); it goes round nether portals unless the bot travels.
- **Colony**: core, member list, radius, who may change it, the scripts' `colony_memory` and job board, persisted as world saved data.
- **Blueprints**: designed outside the game and imported as files, with roles and chest and work spots; the client previews them as ghosts. No network or API calls from inside Minecraft.
- **Bot creation**: bots are crafted, not spawned from nothing.
- **Combat**: bots defend themselves against hostile mobs, and flee at low health.
- **Gating**: `AntCraftConfig` checks whether the server is dedicated and has opted in before allowing any bot to spawn.
