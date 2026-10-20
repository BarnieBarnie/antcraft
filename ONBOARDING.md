# AntCraft onboarding

A quick tour of the codebase for new contributors. For the gameplay design and roadmap, read [README.md](README.md); for the development rules, read [CLAUDE.md](CLAUDE.md).

## What it is

AntCraft is a server-side Fabric mod for Minecraft 26.3 (Java 25, Mojang names). It adds bot entities (`AntBot`) that join a colony (a Colony Core block), take roles and work through a shared job board: chopping trees, mining, farming, crafting, hauling and building. Milestone M5, the blueprint builder, is in progress.

## Source sets

| Directory | Purpose |
|-----------|---------|
| `src/main/java` | Mod logic, runs on the server (about 13k lines) |
| `src/client/java` | Client only: the bot renderer and the colony screen (`K` key) |
| `src/test/java` | JUnit unit tests for pure logic |
| `src/gametest/java` | Fabric GameTests: real bots on a headless server |
| `src/main/resources` | `fabric.mod.json`, lang, textures, recipes, tags, loot tables |
| `tools/skins.py` | Generates the role skins (needs Pillow) |
| `scripts/check.sh` | Gradle wrapper that prints a short PASS/FAIL summary |

The main package is `io.github.barniebarnie.antcraft`.

## Package map

All paths are relative to `src/main/java/io/github/barniebarnie/antcraft`.

- **`AntCraft.java`**: the entrypoint. It loads the config, registers blocks, entities, items, commands and networking, and hooks `ColonyManager.tick` into `END_LEVEL_TICK`. Start reading here.
- **`config/`**: `AntCraftConfig` reads `config/antcraft.properties` and holds the fair-play gate, `botsAllowed(server)`. Dedicated servers must opt in.
- **`registry/`**: `ModBlocks`, `ModEntities`, `ModItems`. Plain registration.
- **`block/`, `item/`**: `ColonyCoreBlock` and its block entity (54 slots), and `BotCoreItem`, which activates a bot.
- **`entity/`**
  - `AntBot`: the mob itself, with a 27-slot inventory, role, home, colony id and scaffold bookkeeping. `registerGoals()` lists every goal in priority order, so it shows how a bot "thinks".
  - `BotRole`, `Tools` (picks the best tool or weapon), `BotSkins`.
  - `goal/`: one goal per role (`LumberjackGoal`, `MinerGoal`, `FarmerGoal`, `BuilderGoal`, `CrafterGoal`, `HaulerGoal`, `GuardGoal`), plus:
    - `ColonyWorkGoal`: the shared base class. It handles travel, reach, the job heartbeat and tool upgrades.
    - `WorkerGoal`: the newer generic job loop. It claims the best job by `JobScore` and runs it on the role's `Executor`. Tree, replant, harvest and plant jobs have moved here already; the other job types still live in their role goals. This migration is ongoing work, as the recent commits show.
  - `navigation/`: `PathFollower` carries out paths step by step (dig, place scaffold, move); `BlockBreaker` breaks blocks over time like a player.
- **`pathing/`**: a custom A* (`PathFinder`) over the `WorldView` interface. `LevelWorldView` backs it with a live world; tests use `GridWorld`. `MoveType`, `Step`, `Path` and `Goal` describe moves and targets.
- **`colony/`**: the colony's brain.
  - `ColonyManager`: the `SavedData` holding all colonies in a level. Its `tick()` runs a **survey** every 5 seconds per colony: it releases stale claims, rechecks blocked steps, surveys crops, the mine, builds, storage, stock and trees, and then assigns roles.
  - `Colony`: a colony's persistent state, saved with Codecs. Note the `withAlternative` codecs, which keep old saves loading. Do not break them.
  - `JobBoard`, `Job`, `JobType`, `JobScore`: the blackboard. Each job has at most one bot, and each bot at most one job.
  - `TaskPlanner` (an order into crafting steps), `StockPlanner` (stock targets into orders), `Stockkeeping` (what moves between the core and storage). These are generic over the item type, so they can be unit-tested without a game.
  - `RoleAssigner`, `Territory`, `TripClaims`, `ToolUpgrade`, `DefaultStock`.
  - `ColonyActions`: changes a player makes. Commands and the screen share it, and it validates client input.
  - `ColonyStatus`: one snapshot used by both `/antcraft colony info` and the screen, so the two never disagree.
- **`work/`**: world logic and plain-data plans.
  - `BuildPlan`: ordered clear and place steps, split into batches. Progress lives in the world: a step is done once the world matches it.
  - `Mineshaft`, `Terraform`, `RegionPlan` (`build copy`), `StorageArea`, `Field`: generators for plans and sites.
  - `TreeScanner`, `Replanting`, `TreeRetry`, `OreRevisit`, `Revisit`: tree and ore logic and retry bookkeeping.
  - `Storage` (moving items between inventories), `GameRecipeBook` (the real recipes behind the `TaskPlanner` interface), `ToolKind`, `PickaxeTier`, `Fuels`.
- **`command/`**: `AntCraftCommands`, the whole `/antcraft` Brigadier tree.
- **`network/`**: `ColonyNetworking`, the payload records between the screen and the server.

## Runtime flow

1. A player places a Colony Core, and `ColonyManager.create` makes a colony.
2. A Bot Core used within 32 blocks of it spawns an `AntBot`, which joins the colony.
3. Every 5 seconds the survey posts jobs (trees, crops, branches, builds), places orders and assigns roles.
4. Each bot's goals claim jobs from the board, find a path, do the work and bring the loot to the core. Haulers move surplus to the storage chests.
5. Everything persists through Codecs in `Colony` and `AntBot` (`ValueInput` and `ValueOutput`).

## Workflow rules

These come from [CLAUDE.md](CLAUDE.md).

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
scripts/check.sh runGameTest -PgameTest='antcraft-gametest:miner_game_test*'
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

- **Move a job type onto `WorkerGoal`**: follow the pattern of the latest commits. Implement `Executor` on the role goal and register it. Mining and build jobs have not moved yet.
- **M5 leftovers**: importing `.nbt` and Litematica files into a `BuildPlan`. `RegionPlan` is the closest example, and it can be unit-tested.
- **Small, isolated logic** in `work/` or the colony planners, covered by unit tests, for fast feedback.
