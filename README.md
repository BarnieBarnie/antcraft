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

Bots find their own way: they walk, jump, swim, drop up to 3 blocks, dig through blocks in the way, and bridge or pillar with scaffold blocks (dirt, cobblestone and similar, see the `antcraft:scaffold_blocks` item tag) from their inventory. They never break chests or other block entities, blocks next to lava or water, or blocks a colony builder placed.

**Colonies:** craft a Colony Core (chest in the middle, iron ingots in the corners, redstone on the sides) and place it. Bots activated within 32 blocks join the colony and use the core, which holds 54 stacks, as their home. The colony surveys its area every 5 seconds and posts jobs for the work it wants done, such as a tree to fell while it is short of logs; each bot claims a different job, so they never get in each other's way. If a bot dies or goes missing, its job is freed for the others. Breaking the core dissolves the colony and drops its contents.

**Stock.** A colony keeps a base stock in its core, so crafters have work and the colony grows on its own. A new colony keeps 2 pickaxes, 1 axe, shovel and hoe, 2 swords (any material; stone when there is cobblestone, else wood), 32 logs, 32 planks, 16 sticks, 64 cobblestone, 32 scaffold blocks, 16 food (bread), 32 torches and one piece of each armor slot. Every survey, the colony orders what is short and can be crafted from what is in the core right now; gathered items such as logs and cobblestone come from tree and mine jobs. Armor needs ingots, so it waits until the colony can smelt. Change the list with `/antcraft colony stock`.

**Workers.** Colony bots are all alike: each takes the job on the colony's job board that suits it best (near it, with the tools and items it carries or the core holds, and a high priority), works it, and takes the next. The only reason a bot idles is that there is nothing to do. Between jobs a bot takes the best sword in the core when it beats what it carries, swaps a pickaxe, axe or hoe it carries for a better one in the core (or orders the best the colony can make), takes down scaffolding left up from a job it was called away from, stores what it carries, shears sheep while the colony is short of wool, and keeps the core and storage (see below). Its skin shows the kind of job it took last. `/antcraft colony info` and the colony screen list each bot with its current job, and how many members are idle.

Colony bots stay around their colony. Any colony bot fights hostile mobs that come near it inside the colony radius (they still fight back anywhere when attacked), and a bot that ends up outside the radius with nothing to do, for example after a chase, walks back to the core. Bots busy with a job outside the radius, like felling a tree on the edge, finish it first. Bots without a colony do no work: they only walk where `goto` sends them and fight.

The jobs:

- **Trees:** see below.
- **Mine branches:** dig a branch of the colony mineshaft and store the haul. A bot takes a pickaxe from the core for it; a colony that wants what mining yields and has no pickaxe orders a wooden one. When a bot's inventory is full of things the colony does not need in the core right away, it drops them in the chest at the nearest landing instead of climbing up.
- **Crops:** harvest a ripe crop around the colony, replant it and store the produce; till and plant a spot of the colony field, watering it with a bucket. A colony lays out a field once it wants wheat (an order or stock target, such as bread).
- **Crafts:** work through colony orders at the core, ingredients first, and turn Bot Cores in storage into new colony bots. So `/antcraft colony order antcraft:bot_core 1` makes the colony build a new bot once the pumpkin, iron and redstone are in the core. A craft whose result has no room in the core waits rather than dropping it, and so does furnace output; crafts that fit go on. Output waiting in the furnace counts as colony stock, so it is not smelted again.
- **Hauls:** empty a mineshaft landing chest into the core. Loose items around the colony are picked up and stored between jobs.
- **Builds and digs:** work through the colony's builds, such as the mineshaft (see below).

Bots use the best tool or weapon they carry, and tools wear out.

**Mineshaft:** once a colony wants what mining yields (an order or stock target for stone or ores), it picks a mineshaft site beside the core and builders dig it: a 1-wide staircase spiralling down a 2x2 column, with a landing and a hall at each mining level (`mineshaftLevels`), and torches every 8 blocks. The colony picks a spot whose column runs into no water, lava or bedrock, and builders wall the stairs and halls with cobblestone where they pass through caves or water. The stairs down to each landing and each hall are separate jobs, so several builders can dig at once: once the stairs reach a landing, one digs its hall while another digs on down. Any bot can walk the stairs without scaffold, and no bot breaks the stairs, their floors or the torches. Miners dig 2-high branches off both sides of each hall, 3 blocks apart, one job per branch, and take the ores they see in the walls and ceiling that their pickaxe can harvest. Like a player, a bot gets no drops from a block that needs a better tool (iron ore needs a stone pickaxe, diamonds an iron one), so before mining a level a miner fetches or orders a pickaxe good enough for the ores the colony wants there (a stone one first when it has only a wooden one). They light the branches with torches from the core as they go. They work the deepest landing whose ores the colony wants (orders or stock targets, e.g. `/antcraft colony stock set minecraft:raw_iron 32`), otherwise the deepest one. A branch with cells no miner can get within reach of, say walled off by bedrock, is tried again every 10 seconds and given up after three tries, so its level can move on.

**Builds.** A build is a list of blocks to clear or place, made with `/antcraft colony build copy`, loaded from a structure file with `/antcraft colony build load`, or by the colony itself for its mineshaft. Builders claim builds, fetch the blocks from the core, and the colony orders what is missing (including a pickaxe for digging, buckets and flint and steel). They place blocks the way a player would: water and lava are poured from buckets (filled at a source nearby when the core has none), waterlogged blocks are placed dry and then filled, doors, beds and tall plants go in whole from one item, and fire is lit with flint and steel. To reach high blocks they pillar up on scaffold blocks from the core, and take the scaffolding down again before they move on. Blocks a builder placed are protected: no other bot breaks them. Steps the colony cannot do, such as an unbreakable block in the way, a block out of reach, a block with no item (like a portal), fire with nothing to stay on, or a fluid with no bucket and no source nearby, are reported in `colony info` and the colony screen; the builders carry on with the rest, and `/antcraft colony blocked clear` gives up on those steps. When the colony finds no flat spot for its storage area, it levels the nearest one that takes the least work (a terraform, see below) and puts its storage there.

**Storage:** once the core is nearly full, the colony picks a flat spot near the core for its storage area: two rows of chests with an aisle between them. The core keeps a working stock (stock targets and what open orders need); bots move the rest to the storage chests between jobs, one category per chest (tools and armor, food, wood, stone and ores, everything else), and bring items back when the core runs short. When a category has no chest with room, the colony orders a chest and a bot places it. A nearly full core with nothing above its working stock keeps only what stock targets and orders need, so a core full of different items still has surplus to store. When the core is full and there is no chest to craft into it, a bot holds a stack of surplus so the chest has a slot, and another if something fills that slot first; `colony info` and the colony screen say when the core is full and why storage cannot take the overflow (no flat spot, or no chest yet). Several bots split the work and never make the same trip. While the colony has a storage area, haul jobs bring everything up from the landing chests; without one, only what the colony wants. Bots keep their tools, weapons and dirt for pillaring when they store what they carry.

**Trees:** a bot on a tree job fells a natural tree (logs touching natural leaves, so your log houses are safe), takes down any scaffold blocks it placed to get up there, picks up the drops, replants a sapling, and brings the logs to the colony core. Before each tree it tops up to 16 scaffold blocks from the core, so it can pillar up to logs it cannot reach from the ground. It leaves the canopy to decay by itself (only breaking leaves in its way). When it has no sapling to replant it posts a replant job, which a bot takes once a sapling lies near the stump or the colony core holds one. A tree is replanted with a sapling of its own kind: a 2x2 tree, like dark oak, only once all four saplings of its kind are there, a 1x1 tree with any sapling if none of its own kind turned up within two minutes (`replantMatchWaitTicks`). A 2x2 site with a spot built over cannot grow its tree back, so it gets a 1x1 tree on one open spot instead. A tree with logs it cannot reach, even with scaffold, is tried again every 30 seconds, and given up after five tries; `colony info` and the colony screen list the trees given up on.

**Commands** (operators only):

| Command | Effect |
|---------|--------|
| `/antcraft status` | Shows whether bots are enabled, and the bot count |
| `/antcraft goto <pos> [bots]` | Sends bots to a position. Without `[bots]`, the nearest bot within 64 blocks |
| `/antcraft stop [bots]` | Cancels the walk target |
| `/antcraft colony info` | Members, idle members and jobs, each bot with its current job, for each order what is still to gather or missing, blocked build steps and trees given up on |
| `/antcraft colony order <item> <count>` | Asks the colony to craft something, e.g. `antcraft:bot_core 2` |
| `/antcraft colony cancel <item>` | Cancels an order |
| `/antcraft colony stock` | Lists what the colony keeps in stock, with how many it has |
| `/antcraft colony stock set <item\|#tag> <count>` | Keeps at least this many in stock, e.g. `#minecraft:pickaxes 3` or `minecraft:torch 16` |
| `/antcraft colony stock remove <item\|#tag>` | Stops keeping it in stock |
| `/antcraft colony mineshaft <pos>` | Moves the colony mineshaft so its stairs start at `<pos>` (ground level); builders dig the new one |
| `/antcraft colony build copy <from> <to> <at>` | Copies the blocks between `<from>` and `<to>` (air included, so it clears too) into a build whose lowest corner is `<at>`; builders build the copy. Block states only, no chest contents |
| `/antcraft colony build load <structure> <at> [rotation] [mirror] [palette]` | Adds a build from a structure file, with the lowest corner of the (mirrored and turned) structure at `<at>`. `<structure>` is one bundled with the mod, such as `antcraft:cobble_step`, or a `minecraft:` one saved with a structure block in this world; tab completion lists them. `[rotation]` is `none` (default), `clockwise_90`, `180` or `counterclockwise_90`. `[mirror]` is `none` (default), `left_right` or `front_back`, like a structure block's mirror; it is applied before the rotation, as a structure block does. `[palette]` picks the block variant of a structure saved with several palettes, such as a vanilla shipwreck: `0` (default) is the first, and a number past the last fails. Air in the structure clears, structure void leaves the block as it is. Block states only, like `build copy`, and the same 32768-block limit |
| `/antcraft colony build list` | Numbered list of the colony's builds with the steps left; the mineshaft is labelled |
| `/antcraft colony build cancel <n>` | Drops build `<n>` from the list (not the mineshaft: move that with `colony mineshaft`) |
| `/antcraft colony terraform <from> <to> <y>` | Levels the box between `<from>` and `<to>` at height `<y>`: bots dig everything above it, fill the holes up to it with dirt, cobblestone, sand or gravel from the core, and fell the trees in it through tree jobs. Ponds and the edge of a river in it are filled from the bank inwards; water above the height builders clear, walling off the water outside the box so it does not flow back in. Lava in it is left alone and reported as blocked. Listed and cancelled as a build |
| `/antcraft colony blocked clear` | Gives up on the build steps builders reported blocked, and the batches waiting on them |
| `/antcraft colony radius <blocks>` | How far from the core the colony looks for trees and crops (default 32) |

**Colony screen:** press `K` near a colony to see its bots with their current jobs, jobs, stock, orders and builds, blocked build steps and trees given up on. The screen can also change the colony without commands: order items or cancel your part of an order (orders the colony placed itself stay), set or remove stock targets, and change the radius. Those edits only change the colony's data, so they work, on the screen and with the matching `/antcraft colony order|cancel|stock|radius` commands, even where bots are not enabled; commands that command bots (`goto`, `stop`) need bots enabled. The server checks every request, reads as well as changes: by default anyone within 64 blocks of the colony may view and change it, or only operators with `colonyActionsRequireOp=true`; anyone else gets "no colony". The `/antcraft` commands, including `colony info`, stay operator-only either way. Colonies have no owners yet, so there is no owner-only mode.

**Config** (`config/antcraft.properties`):

| Key | Default | Meaning |
|-----|---------|---------|
| `enableOnDedicatedServer` | `false` | Must be `true` for bots to work on a dedicated server. When `false`, bots cannot be placed, and any existing bots stand still |
| `maxBotsPerLevel` | `64` | Maximum number of bots per dimension |
| `mineshaftLevels` | `16,-16,-58` | Heights of the landings of new mineshafts: iron and copper, gold and lapis, diamonds and redstone. At least 2 apart, so branches stay clear of other halls; a level too close below another is dropped, with a warning in the log |
| `colonyActionsRequireOp` | `false` | When `true`, only operators can view or change colonies from the colony screen |
| `replantMatchWaitTicks` | `2400` | How long, in ticks (20 per second), a felled 1x1 tree waits for a sapling of its own kind before a lumberjack replants it with any sapling. `0` takes any sapling right away |

## Roadmap

| Milestone | Goal |
|-----------|------|
| M0 | Project scaffold, build, CI (done) |
| M1 | Bot entity (player-skinned mob) with inventory, spawned from a crafted item; walks to a target (done) |
| M2 | Pathfinding that digs, pillars and bridges; lumberjack role chops trees and deposits logs (done) |
| M3 | Colony core block with storage and a shared job board; multiple bots claim tasks (done) |
| M4 | Miner, farmer, hauler and guard roles; hierarchical tasks ("build house" requires "gather 64 logs"); bots craft new bots (done) |
| M5 | Blueprint builder: load `.nbt` structures / Litematica schematics, compute material lists and build order (in progress: `colony build copy`, loading `.nbt` structures with `colony build load`, build plans split into batches for several builders, the mineshaft and `colony terraform` are done; importing Litematica files and material lists for imported structures are left) |
| M6 | AI architect: blueprints designed outside the game (e.g. by an LLM) in a documented JSON format, imported and validated in-game, then built by the colony |

## Architecture (planned)

- **Bot entity**: custom `PathfinderMob` rendered with a player model and skin. Cheap enough for large colonies, and does not appear in the tab list.
- **Navigation**: custom A* over block costs (walk, dig, place, climb).
- **Task system**: a colony-wide job board (blackboard pattern). Bots are generalists: each picks the job that suits it best by distance, what it carries and priority. Jobs can spawn sub-jobs.
- **Colony**: storage, member list, and a goal queue, persisted as world saved data.
- **Bot creation**: bots are crafted, not spawned from nothing. A player crafts the first bot; once the colony has the materials, bots can craft and activate new bots themselves, so the colony grows over time.
- **Combat**: bots defend themselves and the colony against hostile mobs, arming themselves with swords from the core. A patrol job may follow if it turns out to be needed.
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

The bot skins, one per kind of job (see `BotSkins`), are drawn by `tools/skins.py` (needs Pillow). To add or tweak a skin, edit its entry in `SKINS` and regenerate them all:

```sh
python3 tools/skins.py src/main/resources/assets/antcraft/textures/entity/bot
```

Structures for `colony build load` go in `src/main/resources/data/antcraft/structure/` as `.nbt` files, saved with a structure block. In 26.3 a palette entry is `{id, properties}`; a file written in the old `{Name, Properties}` form loads as air.

Targets Minecraft 26.3 with Fabric Loader 0.19.5 and Fabric API.

## License

MIT
