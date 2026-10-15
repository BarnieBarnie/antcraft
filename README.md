# AntCraft

A Fabric mod for Minecraft that adds colonies of virtual bots. They gather materials, run farms and build structures together, working toward a shared goal like an ant colony.

The bots are in-game entities and are not linked to real accounts. This is a hobby project, made for fun in your own worlds.

## Fair-play policy

AntCraft runs entirely on the server side, so it cannot be used as a cheat client on other people's servers. Bots only exist on a server that has the mod installed.

- **Singleplayer / LAN:** enabled by default.
- **Dedicated servers:** disabled by default. The server owner must explicitly set `enabled = true` in the config.
- Bot commands are restricted to operators. Owners can cap the bot count and whitelist who may spawn bots.

## Usage

**Crafting a Bot Core** (shaped):

```
 . P .      P = carved pumpkin
 I R I      I = iron ingot
 . I .      R = block of redstone
```

Use a Bot Core on a block to activate a bot there. Bots pick up items into a 27-slot inventory, which they drop when they die. They fight hostile mobs (except creepers, for now).

**Commands** (operators only):

| Command | Effect |
|---------|--------|
| `/antcraft status` | Shows whether bots are enabled, and the bot count |
| `/antcraft goto <pos> [bots]` | Sends bots to a position. Without `[bots]`, the nearest bot within 64 blocks |
| `/antcraft stop [bots]` | Cancels the walk target |

**Config** (`config/antcraft.properties`):

| Key | Default | Meaning |
|-----|---------|---------|
| `enableOnDedicatedServer` | `false` | Must be `true` for bots to work on a dedicated server. When `false`, bots cannot be placed, and any existing bots stand still |
| `maxBotsPerLevel` | `64` | Maximum number of bots per dimension |

## Roadmap

| Milestone | Goal |
|-----------|------|
| M0 | Project scaffold, build, CI |
| M1 | Bot entity (player-skinned mob) with inventory, spawned from a crafted item; walks to a target |
| M2 | Pathfinding that digs, pillars and bridges; lumberjack role chops trees and deposits logs |
| M3 | Colony core block with storage and a shared job board; multiple bots claim tasks |
| M4 | Miner, farmer, hauler and guard roles; hierarchical tasks ("build house" requires "gather 64 logs"); bots craft new bots |
| M5 | Blueprint builder: load `.nbt` structures / Litematica schematics, compute material lists and build order |
| M6 | AI architect: blueprints designed outside the game (e.g. by an LLM) in a documented JSON format, imported and validated in-game, then built by the colony |

## Architecture (planned)

- **Bot entity**: custom `PathfinderMob` rendered with a player model and skin. Cheap enough for large colonies, and does not appear in the tab list.
- **Navigation**: custom A* over block costs (walk, dig, place, climb).
- **Task system**: a colony-wide job board (blackboard pattern). Bots pick jobs by role, distance and priority. Jobs can spawn sub-jobs.
- **Colony**: storage, member list, and a goal queue, persisted as world saved data.
- **Bot creation**: bots are crafted, not spawned from nothing. A player crafts the first bot; once the colony has the materials, bots can craft and activate new bots themselves, so the colony grows over time.
- **Combat**: bots defend themselves and the colony against hostile mobs. A dedicated guard role patrols the colony area.
- **Blueprints**: designed outside the game and imported as files. No network or API calls from inside Minecraft.
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

Targets Minecraft 26.3 with Fabric Loader 0.19.5 and Fabric API.

## License

MIT
