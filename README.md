# AntCraft

A Fabric mod for Minecraft that adds colonies of virtual bots. They gather materials, run farms and build structures together, working toward a shared goal like an ant colony.

The bots are in-game entities and are not linked to real accounts. This is a hobby project, made for fun in your own worlds.

## Fair-play policy

AntCraft runs entirely on the server side, so it cannot be used as a cheat client on other people's servers. Bots only exist on a server that has the mod installed.

- **Singleplayer / LAN:** enabled by default.
- **Dedicated servers:** disabled by default. The server owner must explicitly set `enabled = true` in the config.
- Bot commands are restricted to operators. Owners can cap the bot count and whitelist who may spawn bots.

## Roadmap

| Milestone | Goal |
|-----------|------|
| M0 | Project scaffold, build, CI |
| M1 | Spawnable bot entity (player-skinned mob) with inventory; walks to a target |
| M2 | Pathfinding that digs, pillars and bridges; lumberjack role chops trees and deposits logs |
| M3 | Colony core block with storage and a shared job board; multiple bots claim tasks |
| M4 | Miner, farmer and hauler roles; hierarchical tasks ("build house" requires "gather 64 logs") |
| M5 | Blueprint builder: load `.nbt` structures / Litematica schematics, compute material lists and build order |
| M6 | AI architect: an LLM designs a blueprint (validated JSON) which the colony builds |

## Architecture (planned)

- **Bot entity**: custom `PathfinderMob` rendered with a player model and skin. Cheap enough for large colonies, and does not appear in the tab list.
- **Navigation**: custom A* over block costs (walk, dig, place, climb).
- **Task system**: a colony-wide job board (blackboard pattern). Bots pick jobs by role, distance and priority. Jobs can spawn sub-jobs.
- **Colony**: storage, member list, and a goal queue, persisted as world saved data.
- **Gating**: `AntCraftConfig` checks whether the server is dedicated and has opted in before allowing any bot to spawn.

## Development

Requires JDK 25.

```sh
./gradlew runClient   # launch Minecraft with the mod
./gradlew build       # jar in build/libs/
```

Targets Minecraft 26.3 with Fabric Loader 0.19.5 and Fabric API.

## License

MIT
