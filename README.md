# AntCraft

A Fabric mod for Minecraft that adds bots you program. Each bot runs a script written in a small subset of Python: it walks where the script sends it, mines, places, crafts, smelts and fills chests, and the script decides what comes next. A bot with nothing to run walks home, through a nether portal if its home is in another dimension, and waits there. Colonies that split work between bots come back later as a library of default scripts (see the Roadmap).

The bots are in-game entities and are not linked to real accounts. This is a hobby project, made for fun in your own worlds.

## Fair-play policy

AntCraft must be installed on the server and on every player's client: it adds items, screens and network messages that a client without it cannot handle. It cannot be used as a cheat client on other people's servers, since bots only exist on a server that has the mod installed.

- **Singleplayer / LAN:** enabled by default.
- **Dedicated servers:** disabled by default. The server owner must explicitly set `enableOnDedicatedServer = true` in the config.
- The `/antcraft` commands are restricted to operators. Server owners can cap the bot count per dimension (`maxBotsPerLevel`).
- Each colony belongs to the player who placed its core. Only that player, the players they trust with the colony (`/antcraft colony trust`) and operators may change it, and a Bot Core's bot joins only a colony its player may change.

## Usage

**Getting started:**

1. Craft a Bot Core and use it on a block: a bot appears there, and you own it.
2. Write a script, such as `config/antcraft/scripts/hello.py`, and run it on the bot with `/antcraft bot run hello`. [docs/scripting.md](docs/scripting.md) describes the language and every action and sense, with examples such as punching a tree into a wooden pickaxe.
3. When the script ends, fails or is stopped (`/antcraft bot stop`), the bot runs `idle.py`: it walks home, where it was first spawned (or where a script moved it with `set_home`), and waits there.
4. To hand out workers rather than commands, hold a Bot Core and set its script with `/antcraft core script hello [args]`: the bot it makes runs `hello` as soon as it appears.

**Crafting a Bot Core** (shaped):

```
 . P .      P = carved pumpkin
 I R I      I = iron ingot
 . I .      R = block of copper
```

Use a Bot Core on a block to activate a bot there. Bots pick up items into a 27-slot inventory. They fight hostile mobs (except creepers, for now), but flee at low health instead (see Reflexes).

**Death.** A dead bot drops its items where it died, like a player, and comes back at its home ten seconds later, in its home's dimension even when it died in another (config `botRespawn`, `botRespawnTicks`). It keeps its name, owner, home, log, `memory` and the portals it remembers, and runs its last script again from the top with the same arguments (or `idle.py`). A respawn still waiting is saved with the world.

Bots find their own way: they walk, jump, swim, drop up to 3 blocks, dig through blocks in the way, and bridge or pillar with scaffold blocks (dirt, cobblestone and similar, see the `antcraft:scaffold_blocks` item tag) from their inventory. They never break chests or other block entities, or blocks next to lava or water, never plan through lava, fire or a fall that hurts, and walk round what players build with (glass, fences, walls, doors) rather than through it. They walk round nether portals too, and never dig at a portal's frame: only `travel` takes a bot through one.

**Reflexes** run every tick before the script and cannot be turned off: a bot steps out of fire and lava, swims up when its air runs low (`botSurfaceAir`), steps out from under falling blocks, and at or below `botFleeHealth` it flees a hostile mob near it, home or away, without fighting back. An action a reflex cuts short ends with `interrupted`.

**Nether.** Bots go between the Overworld and the Nether through nether portals as players do: `travel(portal)` walks into one, and the script carries on in the other dimension. A bot remembers both ends of each portal it went through (`portals()`, the newest 8, kept through death) and forgets one it finds broken. Positions carry their dimension, and an action given a position in another dimension fails with `wrong_dimension`. The end portal is not supported.

**Scripts.** Every bot runs a script, and only a script decides what it does. Without one, or once its script ended, stopped or failed, it runs `idle.py` (walk home, then wait), whether or not it is in a colony; a bot that never ran a script does too. From another dimension than its home's, `idle.py` goes through a nether portal within 32 blocks, or else the nearest one it remembers, and without any waits where it is. When `idle.py` itself ends or fails, it is not started again. A bot's home is where it was first spawned, until a script moves it with `set_home(pos)`.

Scripts `import` modules from the script folders. The mod's default scripts, `idle.py`, the library modules `lib/home.py` (`go_home()`, `at_home()`, `to_home_dimension()`, `portal_out()`) and `lib/shaft.py` (`dig_shaft(levels)` digs and ladders a mineshaft, `to_level(level)` and `to_surface()` climb it) and the colony scripts under `colony/` (see Colonies), are copied into `config/antcraft/scripts/` each time the server starts; the mod never overwrites one you changed, so you can give your bots other idle habits, and a deleted one comes back.

Running scripts are saved with the world: after a reload a bot resumes at the same instruction, inside the action it was doing. If the script or a module it imported changed, it starts again from the top with the same arguments, keeping `memory`.

**Colonies:** craft a Colony Core (chest in the middle, iron ingots in the corners, blocks of copper on the sides) and place it: you own the colony, it stores 54 stacks, and bots activated within 32 blocks of it by a player who may change it join it. A colony is its core, its members, its radius and who may change it; what its members do is up to their scripts (the goal-driven colony, which surveyed its area, posted jobs and built blueprints by itself, was removed in milestone 4 of [docs/scripting.md](docs/scripting.md)). The mod's default colony scripts give a working colony out of the box, and an example to change: run them on members with `/antcraft bot run colony.<role>`, or set one on a Bot Core with `/antcraft core script colony.<role>`.

- `colony.lumberjack` fells the trees within the colony's radius, replants their saplings and puts the logs into the core.
- `colony.builder` takes `build` jobs (`post_job("build", {"blueprint": "antcraft:farm", "at": colony()["core"] + (4, -1, 0)})`), fetches the materials from the core and builds; what the core lacks it asks for with `haul` jobs, and waits. A building with a role is noted in `colony_memory["buildings"]`.
- `colony.hauler` takes `haul` jobs (an item, how many, and optionally from and to which container) and brings the items from the colony's chests and barrels to the core.
- `colony.farmer` tills, plants and harvests the work spots of the colony's farms, with a hoe and seeds from the core: wheat, carrots, potatoes and beetroot. It replants what it harvests and stores the harvest there.

They share `colony/common.py`. Scripts know their colony: `colony()` gives a member its colony's core (with the core's dimension), radius, members and owner, and `colony_memory` is a dict every member shares, saved with the colony. Members split work through the colony's job board: `post_job(kind, data)`, `take_job(kind)`, `finish_job(id)`, and a job whose taker's script ends or is stopped goes back to the board. A member stays one in another dimension than its core. Worlds saved with a goal-driven colony load: the colony keeps its core, members, radius, owner and trusted players, and its jobs, stock targets, orders, projects, builds and mineshaft are dropped. Breaking the core dissolves the colony and drops its contents.

**Blueprints.** The mod keeps a library of buildings: blueprints, loaded with the server's data and again on `/reload`. The mod bundles `antcraft:starter_house` (a small house), `antcraft:cobble_step` (a cobblestone step), `antcraft:warehouse` (a storage building), `antcraft:farm` (a crop farm) and `antcraft:cobble_generator` (a cobblestone generator), and a data pack can add more. A blueprint is a file `data/<namespace>/blueprint/<name>.json`, and its id is that of the file (`<namespace>:<name>`):

```json
{
	"structure": "antcraft:farm_plot",
	"name": "blueprint.antcraft.farm_plot",
	"role": "farm",
	"chests": [[0, 1, 0]],
	"work_spots": [[1, 1, 2], [2, 1, 2]]
}
```

| Field | Required | Meaning |
|-------|----------|---------|
| `structure` | yes | The `.nbt` structure template it builds |
| `name` | yes | Lang key of its display name |
| `role` | no | What it is for: `storage`, `farm` or `generator`. None by default |
| `chests` | no | Chest spots, `[x, y, z]` relative to the structure's origin (its corner at 0, 0, 0 in the structure file). None by default |
| `work_spots` | no | Spots a bot stands on or works at, such as farm plots or a generator's mining block, relative like `chests`. None by default |
| `palette` | no | Which palette of a structure saved with several it builds. `0` (the first) by default. The ghost and the materials in the picker show this palette |

Spots are mirrored and turned with the structure, so they stay on the same blocks however it is placed. A file with an unknown role, a missing field or a bad position is left out, with the reason in the server log. A bot builds a blueprint with the script action `build(blueprint, at, turn=0, mirror=False)`, from the blocks it carries, and `blueprint(id)` gives a script its size, role, materials and spots (see [docs/scripting.md](docs/scripting.md)).

**The Blueprint item** (shapeless: paper and a copper ingot) picks blueprints in game. Right-click with it to open the picker, which lists every blueprint the server has (sent to each client on joining and after `/reload`) with its size, role and the materials it needs; choosing one sets the item to it, and its tooltip names the chosen blueprint.

With a blueprint chosen, holding the item shows a see-through ghost of the structure centred on the block you look at, standing against the face you look at. The list holds no blocks, so it stays small for data packs with many or large structures; the client asks the server for the blocks of the chosen blueprint the first time it shows its ghost, and keeps them until the next `/reload`. A structure too large for one network payload comes in several, which the client puts back together (up to 64 payloads; a larger one shows no ghost), and a request the client could not send is sent again, at most once a second, while the ghost is shown; one the server did not answer within 30 seconds is sent again. The controls, rebindable under AntCraft in the controls menu:

| Control | Does |
|---|---|
| `R` (sneak + `R` the other way), or scroll while sneaking | Turns the ghost a quarter |
| `M` | Mirrors the ghost |

Ghosts are drawn as the blocks themselves, textured and see-through, fully lit so they show at night, without the faces two of their solid blocks share; grass, leaves, vines and other blocks the game colours by biome keep the colour of the biome they stand in, under the ghost's tint; water, and the water of waterlogged blocks, shows as see-through water in the biome's water colour under the tint, drawn as the game draws it: the still texture on its surface, the flowing texture on its sides, each face shaded, and the surface sloping towards lower water of the ghost (the surface keeps the still texture, without a flow direction), without faces between water blocks (lava likewise); any other block with no model shows as an outline. A ghost's blocks and colours are worked out once and kept while it stays the same, not every frame; when a chunk loads within 8 blocks of a ghost, its colours are worked out again, so a ghost shown before the chunk's biomes arrived gets their colours. At most 4096 blocks of a ghost are drawn.

**Commands** (operators only, but a colony's owner may also use `colony trust`, `colony trust list` and `colony untrust` on it):

| Command | Effect |
|---------|--------|
| `/antcraft status` | Shows whether bots are enabled, and the bot count |
| `/antcraft script list` | Lists the scripts found in `config/antcraft/scripts/` and `<world>/antcraft/scripts/`, with the error of each that does not compile. A script is named by its path without `.py`, with dots for folders (`lib/trees.py` is `lib.trees`); the world folder wins on a name clash |
| `/antcraft script reload` | Loads the script folders again; a bot running a script whose file changed starts the new version from the top (one that no longer compiles stops, with the error in its log and its owner's chat) |
| `/antcraft bot [bots] run <script> [args]` | Starts a script on bots (without `[bots]`, the nearest bot within 64 blocks), replacing what they ran; `[args]` are given to it as the list `ARGS`. A player who runs it becomes the bots' owner |
| `/antcraft bot [bots] stop` | Stops the bots' scripts (formerly `/antcraft stop`); they then run `idle.py`, walking home and waiting there |
| `/antcraft bot [bots] status` | Each bot's script, whether it is running, finished, failed or stopped, the line it is at, its current action and how many instructions it ran |
| `/antcraft bot [bots] log [n] [level]` | The last `n` (default 10) lines of each bot's log at or above `level` (`debug`, the default, `info`, `warn`, `error` or `print`), each with the game time it was written at |
| `/antcraft core script <script> [args]` | Sets the script of the Bot Core in your main hand: the bot it makes starts that script with those args at once, owned by you, without `bot run`. The tooltip names it; cores with different scripts do not stack. A script that does not exist or does not compile is refused with its error. `/antcraft core script clear` takes it off, so the bot runs `idle.py` |
| `/antcraft profile start\|stop\|report` | Measures where server tick time goes: `start` forgets what was measured and starts, `stop` stops, `report` lists the ticks measured with their average and worst time and which tick was the worst, then each part of the bots' work (a bot's tick `bot.tick`, its script `bot.script`, pathfinding for a walk `path.find` and for `reachable` `path.plan`) with its time per tick, worst tick and calls, the costliest first. Parts nest, such as pathfinding inside a bot's tick, so they do not add up to the tick |
| `/antcraft colony info` | The nearest colony's core, members and radius, and its owner and trusted players |
| `/antcraft colony trust <player> [colony]` | Lets a player change the colony as its owner can. The colony's owner may run this, `untrust` and `trust list` without operator rights; trusted players may change the colony but not whom it trusts. Acts on the nearest colony within 64 blocks that you may trust (your own, or any for operators), so another player's colony nearer does not get in the way, or on the colony whose core is at `[colony]` (within 64 blocks), to pick one of two colonies close together; a named colony you may not trust is refused |
| `/antcraft colony untrust <player> [colony]` | Takes that back |
| `/antcraft colony trust list [colony]` | Names the owner and the trusted players of the same colony `trust` would act on, so an owner who is not an operator can see whom their colony trusts; another player's colony is refused |
| `/antcraft colony radius <blocks>` | Sets the nearest colony's radius (default 32) |

**Server load:** 30 bots running scripts keep well within the server's 50 ms a tick (see the bot benchmark under Development). All bots together expand at most 2000 path nodes a tick (`PathBudget`): a bot whose path search runs out of them waits where it stands and goes on with it the next tick, so many bots planning at once spread their searches over several ticks rather than stall one. A search asks the world about each block once, and reads blocks from the loaded chunks without asking the chunk source each time. `find_nearest` and `scan` share a budget of 32768 blocks a tick the same way, and senses that touch the world at once (`craft`, `count`, `entities` and the like) count about their time in script instructions, so a loop of them cannot stall the server either.

**Config** (`config/antcraft.properties`):

| Key | Default | Meaning |
|-----|---------|---------|
| `enableOnDedicatedServer` | `false` | Must be `true` for bots to work on a dedicated server. When `false`, bots cannot be placed, and any existing bots stand still |
| `maxBotsPerLevel` | `64` | Maximum number of bots per dimension |
| `colonyActionsRequireOp` | `false` | When `true`, only operators can change colonies, owners and trusted players included, and Bot Core bots of other players join no colony |
| `scriptInstructionsPerTick` | `2000` | How many instructions each bot's script may run a tick; a script that uses them up goes on the next tick, so an endless loop costs a busy bot, not a frozen server. At most `20000`: a higher value is lowered with a warning in the log, since every bot may use its whole budget every tick (30 bots at 20000 are 600000 instructions a tick on the server thread) |
| `scriptChatLevel` | `error` | The lowest level of a script's output (`debug`, `info`, `warn` or `error`) that goes to the chat of its bot's owner; everything goes to the bot's log, and `print` and `say` always show in chat |
| `botRespawn` | `true` | Whether a dead bot comes back at its home |
| `botRespawnTicks` | `200` | Ticks a dead bot waits before it comes back |
| `botFleeHealth` | `6` | Health (of 20) at or below which a bot flees a hostile mob within 8 blocks instead of fighting; `0` never |
| `botSurfaceAir` | `100` | Air (of 300 ticks) below which a bot under water swims up to breathe; `0` never |
| `botsLoadChunks` | `true` | A bot running a script keeps the chunks around it loaded, so it works on when its owner walks away, and `goto` loads (and generates, if need be) the chunks on its way. When `false`, `goto` stops with `not_loaded` at the edge of loaded terrain |

The goal-driven colony's options, `mineshaftLevels` and `replantMatchWaitTicks`, are gone: a config file that still has them loads, and they are dropped when the file is written again.

## Roadmap

The mod has turned from goal-driven colonies into **scripted bots**: the mod gives bots robust actions and senses, and players write their behaviour in a small subset of Python. The colony comes back later as a library of default scripts. The design, the language and the milestones are in [docs/scripting.md](docs/scripting.md).

| Milestone | Goal |
|-----------|------|
| 1 | Language core: tokenizer, parser, compiler and a pausable VM with the builtins (done) |
| 2 | Scripts in the game: script folders, `script list`/`reload`, `bot run`/`stop`/`status`/`log`, the instruction budget, output and logging, `say`, `wait`, `pos`, `home`/`set_home`, `goto` with chunk loading, and `idle.py` (done) |
| 3 | Core actions and senses: `mine`, `place`, `pick_up`, `craft`, `smelt`, `find_nearest`, `scan`, containers, `memory`; a tree punched into a wooden pickaxe by a script (done) |
| 4 | The goal-driven colony removed; its engine parts kept behind the actions; every bot without a script runs `idle.py` (done) |
| 5 | Reflexes, saving and resuming scripts, `import` and the default `lib/` folder, respawning at home, travel through nether portals (done) |
| 6 | Mining library: `lib/shaft.py`, the mineshaft, dug and laddered, every time (started: the senses `mineshaft(levels)` and `mineshaft_at(top, levels)` give a script where a ladder shaft goes and its ladders, landings, chests, halls, branches and torches, and `lib/shaft.py` digs it, past gravel, air and water, and climbs it) |
| 7 | Colony again: shared colony memory (`colony()`, `colony_memory`), a script-level job board (`post_job`, `take_job`), Bot Cores that carry a script, `build` as an action, and the default colony scripts `colony.lumberjack`, `colony.hauler`, `colony.builder` and `colony.farmer` (done) |
| 8 | Later: per-player limits, more of Python, an in-game script viewer, scripts in data packs, Litematica import |

Done before the change of direction: the bot entity, pathfinding that digs, pillars and bridges, the colony core and job board, the lumberjack, miner, farmer, hauler, guard and crafter work, blueprints with roles, the placement and build ghosts, and the warehouse, farm and cobblestone generator. Milestone 4 removed the job board, the work goals, stock planning, projects and placing blueprints for a colony; what the actions need of them (pathfinding, block breaking, crafting, the mineshaft and ore vein plans, build plans) stays.

## Architecture

- **Bot entity**: custom `PathfinderMob` rendered with a player model and skin. Cheap enough for many bots, and does not appear in the tab list.
- **Scripts**: each bot runs one script at a time on a pausable VM with a per-tick instruction budget (`script/lang`); actions such as `goto`, `mine` and `craft` take ticks and pause the script until they are done (`script/*Api`). The VM's state is plain data, saved with the bot and resumed on load. See [docs/scripting.md](docs/scripting.md).
- **Reflexes**: Java, always on, before the script each tick (`entity/Reflexes`).
- **Navigation**: custom A* over block costs (walk, dig, place, climb, swim), followed by `PathFollower`, with a shared node budget (`PathBudget`); it goes round nether portals unless the bot travels.
- **Colony**: core, member list, radius, who may change it, the scripts' `colony_memory` and job board, persisted as world saved data.
- **Blueprints**: designed outside the game and imported as files, with roles and chest and work spots; the client previews them as ghosts. No network or API calls from inside Minecraft.
- **Bot creation**: bots are crafted, not spawned from nothing.
- **Combat**: bots defend themselves against hostile mobs, and flee at low health.
- **Gating**: `AntCraftConfig` checks whether the server is dedicated and has opted in before allowing any bot to spawn.

## Development

Requires JDK 25.

```sh
./gradlew runClient   # launch Minecraft with the mod
./gradlew test        # unit tests
./gradlew runGameTest # bot behaviour tests on a headless server
./gradlew build       # runs both test suites; jar in build/libs/
```

Development is test-first. See [CLAUDE.md](CLAUDE.md) for the workflow.

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

## License

MIT
