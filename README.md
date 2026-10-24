# AntCraft

A Fabric mod for Minecraft that adds colonies of virtual bots. You plan, the colony does the chores: pick a building from a library of blueprints, see it as a ghost where it will stand and place it, and the bots level the site, gather or craft the materials and build it. A warehouse becomes the colony's storage, a farm its field and a cobblestone generator a source of stone; between builds the bots fell trees, dig the mine, craft what the colony is short of and keep its stores, like an ant colony.

The bots are in-game entities and are not linked to real accounts. This is a hobby project, made for fun in your own worlds.

## Fair-play policy

AntCraft must be installed on the server and on every player's client: it adds items, screens and network messages that a client without it cannot handle. It cannot be used as a cheat client on other people's servers, since bots only exist on a server that has the mod installed.

- **Singleplayer / LAN:** enabled by default.
- **Dedicated servers:** disabled by default. The server owner must explicitly set `enableOnDedicatedServer = true` in the config.
- The `/antcraft` commands are restricted to operators. Server owners can cap the bot count per dimension (`maxBotsPerLevel`).
- Each colony belongs to the player who placed its core. Only that player, the players an operator trusts with the colony (`/antcraft colony trust`) and operators may view or change it from the colony screen, and a Bot Core's bot joins only a colony its player may change, so other players on a shared server cannot order, build or plan in your colony.

## Usage

**Getting started:**

1. Craft a Colony Core and place it: you own the colony, and an operator can let other players help (`/antcraft colony trust`).
2. Craft a Bot Core and use it within 32 blocks of the core; the bot joins the colony. Craft more for more hands, or order them from the colony (see Crafts below).
3. The colony makes its starter tool kit and, once it wants stone or ores, digs a mineshaft beside the core. Everything else is your plan.
4. Craft a Blueprint, pick a building with it, and place it: a warehouse for storage, a farm for crops, a cobblestone generator, or a building of your own from a data pack. The colony builds it and puts it to use.
5. Ask for more with orders and stock targets on the colony screen (`K`).

**Crafting a Bot Core** (shaped):

```
 . P .      P = carved pumpkin
 I R I      I = iron ingot
 . I .      R = block of copper
```

Use a Bot Core on a block to activate a bot there. Bots pick up items into a 27-slot inventory, which they drop when they die. They fight hostile mobs (except creepers, for now).

Bots find their own way: they walk, jump, swim, drop up to 3 blocks, dig through blocks in the way, and bridge or pillar with scaffold blocks (dirt, cobblestone and similar, see the `antcraft:scaffold_blocks` item tag) from their inventory. They never break chests or other block entities, blocks next to lava or water, or blocks a colony builder placed.

**Colonies:** craft a Colony Core (chest in the middle, iron ingots in the corners, blocks of copper on the sides) and place it. Bots activated within 32 blocks join the colony and use the core, which holds 54 stacks, as their home. The colony surveys its area every 5 seconds and posts jobs for the work it wants done, such as a tree to fell while it is short of logs; each bot claims a different job, so they never get in each other's way. If a bot dies or goes missing, its job is freed for the others. Breaking the core dissolves the colony and drops its contents.

A new colony starts two projects by itself: its starter tool kit (2 pickaxes, an axe, a shovel, a hoe and 2 swords), then a mineshaft next to the core, which it digs once it wants what mining yields. Everything else it builds is the player's choice: place a farm for crops and a warehouse for storage (see Blueprints below). Until then the colony plants no field, and keeps everything in its core. Colonies from older versions keep the field and storage area they laid out themselves.

**Stock.** A colony keeps a base stock in its core, so crafters have work and the colony grows on its own. A new colony keeps 2 pickaxes, 1 axe, shovel and hoe, 2 swords (any material; stone when there is cobblestone, else wood), 32 logs, 32 planks, 16 sticks, 64 cobblestone, 32 scaffold blocks, 16 food (bread), 32 torches and one piece of each armor slot. Every survey, the colony orders what is short and can be crafted from what is in the core right now; gathered items such as logs and cobblestone come from tree and mine jobs. Armor needs ingots, so it waits until the colony can smelt. Change the list with `/antcraft colony stock`.

**Workers.** Colony bots are all alike: each takes the job on the colony's job board that suits it best (near it, with the tools and items it carries or the core holds, and a high priority), works it, and takes the next. The only reason a bot idles is that there is nothing to do. Between jobs a bot takes the best sword in the core when it beats what it carries, swaps a pickaxe, axe or hoe it carries for a better one in the core (or orders the best the colony can make), takes down scaffolding left up from a job it was called away from, stores what it carries, shears sheep while the colony is short of wool, and keeps the core and storage (see below). Its skin shows the kind of job it took last. `/antcraft colony info` and the colony screen list each bot with its current job, and how many members are idle.

Colony bots stay around their colony. Any colony bot fights hostile mobs that come near it inside the colony radius (they still fight back anywhere when attacked), and a bot that ends up outside the radius with nothing to do, for example after a chase, walks back to the core. Bots busy with a job outside the radius, like felling a tree on the edge, finish it first. Bots without a colony do no work: they only walk where `goto` sends them and fight.

The jobs:

- **Trees:** see below.
- **Mine branches:** dig a branch of the colony mineshaft and store the haul. A bot takes a pickaxe from the core for it; a colony that wants what mining yields and has no pickaxe orders a wooden one. When a bot's inventory is full of things the colony does not need in the core right away, it drops them in the chest at the nearest landing instead of climbing up.
- **Crops:** harvest a ripe crop around the colony, replant it and store the produce; till and plant a spot of the colony field, watering it with a bucket. Players choose where the colony grows its crops by placing a farm: a blueprint with role `farm`, such as the bundled `antcraft:farm` (a 5x5 crop farm built from 24 dirt and a water bucket: dirt round a water block in the middle, which keeps every plot wet). Its work spots are its plots; once a farm is built, its plots, turned and mirrored with the building, are the colony field in place of the one a colony from an older version laid out itself, and with several farms built the field holds the plots of all of them, oldest farm first. Bots till, plant, harvest and replant them as they do any field. A colony lays out no field of its own: while it has no farm, an order or stock target that needs wheat (such as bread) says in `colony info` and the colony screen that it needs a farm.
- **Crafts:** work through colony orders at the core, ingredients first, and turn Bot Cores in storage into new colony bots. So `/antcraft colony order antcraft:bot_core 1` makes the colony build a new bot once the pumpkin, iron and redstone are in the core. A craft whose result has no room in the core waits rather than dropping it, and so does furnace output; crafts that fit go on. Output waiting in the furnace counts as colony stock, so it is not smelted again.
- **Hauls:** empty a mineshaft landing chest into the core. Loose items around the colony are picked up and stored between jobs.
- **Builds and digs:** work through the colony's builds, such as the mineshaft (see below).

Bots use the best tool or weapon they carry, and tools wear out.

**Mineshaft:** once a colony wants what mining yields (an order or stock target for stone or ores), it picks a mineshaft site beside the core and builders dig it: a 1x1 ladder shaft, a column with a ladder on one wall at every height, entered through an oak trapdoor in the ground, with a small landing chamber beside the column at each mining level (`mineshaftLevels`) that holds the landing chest and a torch. Builders dig the column from the top down and put up the ladders as they go; the colony orders the ladders, the trapdoor, torches and chests the dig needs, and a dig step waiting for them is reported like any build waiting for items. The colony picks a spot whose column runs into no water, lava or bedrock, and builders wall the column with cobblestone where it passes through caves or water, so every ladder has a block behind it and no water comes in. The column down to each landing is a separate job. Bots climb the ladders both ways and pass each other on them, and no bot breaks the ladders, the walls behind them, the floors or the torches. Colonies that dug a mineshaft before keep it: a 2-wide staircase spiralling round a 2x2 column, or the older 1-wide staircase. From each landing a hall leads away from the shaft, which miners dig first. Miners dig 2-high branches off both sides of each hall, 3 blocks apart, one job per branch, and take the ores they see in the walls and ceiling that their pickaxe can harvest. Like a player, a bot gets no drops from a block that needs a better tool (iron ore needs a stone pickaxe, diamonds an iron one), so before mining a level a miner fetches or orders a pickaxe good enough for the ores the colony wants there (a stone one first when it has only a wooden one). They light the branches with torches from the core as they go. They work the deepest landing whose ores the colony wants (orders or stock targets, e.g. `/antcraft colony stock set minecraft:raw_iron 32`), otherwise the deepest one. A branch with cells no miner can get within reach of, say walled off by bedrock, is tried again every 10 seconds and given up after three tries, so its level can move on.

**Builds.** A build is a list of blocks to clear or place, made with `/antcraft colony build copy`, loaded from a structure file with `/antcraft colony build load`, or by the colony itself for its mineshaft. Builders claim builds, fetch the blocks from the core, and the colony orders what is missing (including a pickaxe for digging, buckets and flint and steel). They place blocks the way a player would: water and lava are poured from buckets (filled at a source nearby when the core has none) once the blocks the build puts beside and under them stand, so they do not flow out, waterlogged blocks are placed dry and then filled, doors, beds and tall plants go in whole from one item, and fire is lit with flint and steel. Lava is fetched from a source in the colony or beside the mine's dug-out cells, filled from a spot on solid ground where no lava is in, under or next to the bot, such as the rim of a pool; a source with no such spot is skipped. An order for a full bucket, such as `/antcraft colony order minecraft:lava_bucket 1`, is gathered the same way by an idle bot with an empty bucket and stored in the core; with no source in reach the bot stays put and a step waiting on lava stays reported. To reach high blocks they pillar up on scaffold blocks from the core, and take the scaffolding down again before they move on. Blocks a builder placed are protected: no other bot breaks them. Steps the colony cannot do, such as an unbreakable block in the way, a block out of reach, a block with no item (like a portal), fire with nothing to stay on, or a fluid with no bucket and no source nearby, are reported in `colony info` and the colony screen; the builders carry on with the rest, and `/antcraft colony blocked clear` gives up on those steps.

**Blueprints.** The colony keeps a library of buildings it knows: blueprints, loaded with the server's data and again on `/reload`. The mod bundles `antcraft:starter_house` (a small house), `antcraft:cobble_step` (a cobblestone step), `antcraft:warehouse` (a storage building, see Storage below), `antcraft:farm` (a crop farm, see Crops) and `antcraft:cobble_generator` (a cobblestone generator, see below), and a data pack can add more. A blueprint is a file `data/<namespace>/blueprint/<name>.json`, and its id is that of the file (`<namespace>:<name>`):

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
| `structure` | yes | The `.nbt` structure template it builds, as `colony build load` takes it |
| `name` | yes | Lang key of its display name |
| `role` | no | What the colony uses it for: `storage`, `farm` or `generator`. None by default |
| `chests` | no | Chest spots, `[x, y, z]` relative to the structure's origin (its corner at 0, 0, 0 in the structure file). None by default |
| `work_spots` | no | Spots a bot stands on or works at, such as farm plots or a generator's mining block, relative like `chests`. None by default |

Spots are mirrored and turned with the structure, so they stay on the same blocks however it is placed. A file with an unknown role, a missing field or a bad position is left out, with the reason in the server log.

A blueprint is placed for the colony with `/antcraft colony build place` (or from the client, through the same check). Only the colony's owner, the players it trusts and operators may place one, and the site must lie inside the colony's territory and keep clear of the core, the mineshaft, other builds and placed blueprints, the colony's field, storage and quarry, and blocks builders placed, at any height; otherwise the placement is refused with the reason. When the ground under the site is uneven or something stands in it, the colony first levels it (a terraform at the height just below the structure, filling holes from two blocks further down), then builds the structure. A placed blueprint is listed under its name in `colony build list`, `colony project list` and the colony screen, and the colony keeps it, with its role and its chests and work spots as built, once it stands.

The bundled **cobblestone generator** (`antcraft:cobble_generator`, role `generator`, 6x3x3) is built from 32 cobblestone, a water bucket and a lava bucket, the way a player builds one: in a walled channel on a floor, water flows from its source over a hole and falls into it, so it never spreads further, and a covered lava source sits on the other side of a cobblestone block, the mining block. Mined, lava flows into its place, meets the water and turns to cobblestone again. Its first work spot is that mining block, where cobblestone forms, and its second the spot in front of it where a bot stands to mine it. Builders fetch the lava at a pool in the territory when the core has none, as for any lava step. Once a generator stands, the colony uses it instead of mining for cobblestone: while it is short of cobblestone (a stock target or an order), it posts one job per generator, so one bot works each. A bot with a pickaxe (fetched from the core, as for a branch) stands on the second work spot, mines the cobblestone each time it forms and stores it in the core, and stops once the core and what it carries make up what the colony wants. The job scores above a mineshaft branch of the same priority, and the colony no longer starts a mineshaft, opens a quarry or posts branches for cobblestone alone, so the mineshaft is dug for ores. No bot breaks the generator's blocks, its mining block included (only the generator job mines that), and no builder or farmer scoops up its water or lava, nor any other fluid a build placed.

**The Blueprint item** (shapeless: paper and a copper ingot) picks blueprints in game instead of by id. Right-click the air with it to open the picker, which lists every blueprint the server has (sent to each client on joining and after `/reload`) with its size, role and the materials it needs; choosing one sets the item to it, and its tooltip names the chosen blueprint. Right-clicking a block with no blueprint chosen opens the picker too.

With a blueprint chosen, holding the item shows a see-through ghost of the structure centred on the block you look at, standing against the face you look at. The controls, rebindable under AntCraft in the controls menu:

| Control | Does |
|---|---|
| `R` (sneak + `R` the other way), or scroll while sneaking | Turns the ghost a quarter |
| `M` | Mirrors the ghost |
| Right-click a block | Places the blueprint where the ghost stands, turned and mirrored as shown, for the nearest colony you may place for |
| Sneak + right-click a placed build's amber ghost, twice | Cancels that build |

The ghost is pale blue where the placement looks fine and red where the client can tell the server would refuse it: no colony you may place for within 64 blocks, a site outside the territory, or over the core, the mineshaft, a build, a placed blueprint, an area the colony uses or blocks builders placed (the client asks the server for these every second while you hold the item). The server still checks the placement and answers on the action bar.

Placed blueprints still being built show as amber ghosts to the colony's owner and the players it trusts within 64 blocks, whether or not they hold a Blueprint: each block the builders still have to place, which disappears once it stands (the server looks once a second and sends what changed). While the site is levelled, the whole structure shows. To drop a placed build, sneak and right-click one of its ghost blocks with a Blueprint, within 32 blocks: the action bar asks to confirm and the build turns red; a second sneak + right-click on it within five seconds cancels it, as `/antcraft colony build cancel` does. The server checks that you may act on the colony. Sneak + right-click anywhere else places the blueprint as usual.

**Storage:** players choose where the colony keeps its storage by placing a warehouse: a blueprint with role `storage`, such as the bundled `antcraft:warehouse` (two rows of five chests with an aisle between them, built from ten chests). Once a warehouse is built, its chests, at the blueprint's chest spots as turned and mirrored with the building, are the colony's storage chests; a colony from an older version keeps the storage area it picked itself as storage too, after the warehouses, so what its chests hold is still counted and fetched; with several warehouses built, the colony uses the chests of all of them, oldest warehouse first. A colony picks no storage spot of its own: until a warehouse stands, everything stays in the core. The core keeps a working stock (stock targets and what open orders need); bots move the rest to the storage chests between jobs, one category per chest (tools and armor, food, wood, stone and ores, everything else), and bring items back when the core runs short. When a category has no chest with room, the colony orders a chest and a bot places it. A nearly full core with nothing above its working stock keeps only what stock targets and orders need, so a core full of different items still has surplus to store. When the core is full and there is no chest to craft into it, a bot holds a stack of surplus so the chest has a slot, and another if something fills that slot first; `colony info` and the colony screen say when the core is full and why storage cannot take the overflow (no warehouse yet, so place one, or no chest yet). Several bots split the work and never make the same trip. While the colony has a storage area, haul jobs bring everything up from the landing chests; without one, only what the colony wants. Bots keep their tools, weapons and dirt for pillaring when they store what they carry.

**Trees:** a bot on a tree job fells a natural tree (logs touching natural leaves, so your log houses are safe), takes down any scaffold blocks it placed to get up there, picks up the drops, replants a sapling, and brings the logs to the colony core. Before each tree it tops up to 16 scaffold blocks from the core, so it can pillar up to logs it cannot reach from the ground. It leaves the canopy to decay by itself (only breaking leaves in its way). When it has no sapling to replant it posts a replant job, which a bot takes once a sapling lies near the stump or the colony core holds one. A tree is replanted with a sapling of its own kind: a 2x2 tree, like dark oak, only once all four saplings of its kind are there, a 1x1 tree with any sapling if none of its own kind turned up within two minutes (`replantMatchWaitTicks`). A 2x2 site with a spot built over cannot grow its tree back, so it gets a 1x1 tree on one open spot instead. A tree with logs it cannot reach, even with scaffold, is tried again every 30 seconds, and given up after five tries; `colony info` and the colony screen list the trees given up on.

**Commands** (operators only):

| Command | Effect |
|---------|--------|
| `/antcraft status` | Shows whether bots are enabled, and the bot count |
| `/antcraft goto <pos> [bots]` | Sends bots to a position. Without `[bots]`, the nearest bot within 64 blocks |
| `/antcraft stop [bots]` | Cancels the walk target |
| `/antcraft colony info` | Members, idle members and jobs, the owner and trusted players, each bot with its current job, for each order what is still to gather or missing, blocked build steps and trees given up on |
| `/antcraft colony trust <player>` | Lets a player change the colony as its owner can |
| `/antcraft colony untrust <player>` | Takes that back |
| `/antcraft colony order <item> <count>` | Asks the colony to craft something, e.g. `antcraft:bot_core 2` |
| `/antcraft colony cancel <item>` | Cancels an order |
| `/antcraft colony stock` | Lists what the colony keeps in stock, with how many it has |
| `/antcraft colony stock set <item\|#tag> <count>` | Keeps at least this many in stock, e.g. `#minecraft:pickaxes 3` or `minecraft:torch 16` |
| `/antcraft colony stock remove <item\|#tag>` | Stops keeping it in stock |
| `/antcraft colony mineshaft <pos>` | Moves the colony mineshaft so its ladder shaft starts at `<pos>` (ground level); builders dig the new one |
| `/antcraft colony build copy <from> <to> <at>` | Copies the blocks between `<from>` and `<to>` (air included, so it clears too) into a build whose lowest corner is `<at>`; builders build the copy. Block states only, no chest contents |
| `/antcraft colony build load <structure> <at> [rotation] [mirror] [palette]` | Adds a build from a structure file, with the lowest corner of the (mirrored and turned) structure at `<at>`. `<structure>` is one bundled with the mod, such as `antcraft:cobble_step`, or a `minecraft:` one saved with a structure block in this world; tab completion lists them. `[rotation]` is `none` (default), `clockwise_90`, `180` or `counterclockwise_90`. `[mirror]` is `none` (default), `left_right` or `front_back`, like a structure block's mirror; it is applied before the rotation, as a structure block does. `[palette]` picks the block variant of a structure saved with several palettes, such as a vanilla shipwreck: `0` (default) is the first, and a number past the last fails. Air in the structure clears, structure void leaves the block as it is. Block states only, like `build copy`, and the same 32768-block limit |
| `/antcraft colony build list` | Numbered list of the colony's builds with the steps left; the mineshaft is labelled |
| `/antcraft colony build cancel <n>` | Drops build `<n>` from the list (not the mineshaft: move that with `colony mineshaft`) |
| `/antcraft colony build place <blueprint> <at> [rotation] [mirror]` | Places a blueprint (see Blueprints above), such as `antcraft:starter_house`, for the nearest colony to build, with the lowest corner of the (mirrored and turned) structure at `<at>`; tab completion lists the loaded blueprints. `[rotation]` and `[mirror]` are as for `build load`. Refused, with the reason, for a player who is neither the colony's owner nor trusted, an unknown blueprint, or a site outside the territory or over the core, the mineshaft, another build or blocks builders placed. The colony levels uneven ground under the site first, then builds the structure; both are listed under the blueprint's name and cancelled as a build or project |
| `/antcraft colony project list` | Numbered list of the colony's projects (the tool kit, the mineshaft, placed blueprints and other builds) with their priority, builds, steps left and what they wait for |
| `/antcraft colony project cancel <n>` | Drops project `<n>` with its builds (not the mineshaft) |
| `/antcraft colony project priority <n> <priority>` | Sets the priority of project `<n>`, from -100 to 100 (not the mineshaft); higher goes first |
| `/antcraft colony terraform <from> <to> <y>` | Levels the box between `<from>` and `<to>` at height `<y>`: bots dig everything above it, fill the holes up to it with dirt, cobblestone, sand or gravel from the core, and fell the trees in it through tree jobs. Ponds and the edge of a river in it are filled from the bank inwards; water above the height builders clear, walling off the water outside the box so it does not flow back in. Lava in it is left alone and reported as blocked. Listed and cancelled as a build |
| `/antcraft colony blocked clear` | Gives up on the build steps builders reported blocked, and the batches waiting on them |
| `/antcraft colony radius <blocks>` | How far from the core the colony looks for trees and crops (default 32) |

**Colony screen:** press `K` near a colony to see its bots with their current jobs, jobs, stock, orders and builds, blocked build steps and trees given up on. The screen can also change the colony without commands: order items or cancel your part of an order (orders the colony placed itself stay), set or remove stock targets, and change the radius. Those edits only change the colony's data, so they work, on the screen and with the matching `/antcraft colony order|cancel|stock|radius` commands, even where bots are not enabled; commands that command bots (`goto`, `stop`) need bots enabled. The screen shows the colony's owner and trusted players. The server checks every request, reads as well as changes: the owner and trusted players may view and change their colony from anywhere within 64 blocks of it, and operators any colony, or only operators with `colonyActionsRequireOp=true`; anyone else gets "no colony" (the screen shows the nearest colony they may use). A colony saved before colonies had owners has none yet: anyone within 64 blocks may use it, and the first to open the screen there claims it. The `/antcraft` commands, including `colony info` and `colony trust`, stay operator-only either way.

**Config** (`config/antcraft.properties`):

| Key | Default | Meaning |
|-----|---------|---------|
| `enableOnDedicatedServer` | `false` | Must be `true` for bots to work on a dedicated server. When `false`, bots cannot be placed, and any existing bots stand still |
| `maxBotsPerLevel` | `64` | Maximum number of bots per dimension |
| `mineshaftLevels` | `16,-16,-58` | Heights of the landings of new mineshafts: iron and copper, gold and lapis, diamonds and redstone. At least 2 apart, so branches stay clear of other halls; a level too close below another is dropped, with a warning in the log |
| `colonyActionsRequireOp` | `false` | When `true`, only operators can view or change colonies from the colony screen, owners and trusted players included, and Bot Core bots of other players join no colony |
| `replantMatchWaitTicks` | `2400` | How long, in ticks (20 per second), a felled 1x1 tree waits for a sapling of its own kind before a lumberjack replants it with any sapling. `0` takes any sapling right away |

## Roadmap

| Milestone | Goal |
|-----------|------|
| M0 | Project scaffold, build, CI (done) |
| M1 | Bot entity (player-skinned mob) with inventory, spawned from a crafted item; walks to a target (done) |
| M2 | Pathfinding that digs, pillars and bridges; lumberjack role chops trees and deposits logs (done) |
| M3 | Colony core block with storage and a shared job board; multiple bots claim tasks (done) |
| M4 | Miner, farmer, hauler and guard roles; hierarchical tasks ("build house" requires "gather 64 logs"); bots craft new bots (done) |
| M5 | Blueprint builder: load `.nbt` structures / Litematica schematics, compute material lists and build order (in progress: `colony build copy`, loading `.nbt` structures with `colony build load`, build plans split into batches for several builders, fluids poured once their walls stand, the ladder mineshaft and `colony terraform` are done; importing Litematica files and material lists for imported structures are left) |
| Planner | The player plans, the colony runs the chores (in progress: colony owners and trusted players, a blueprint library with roles, chest spots and work spots, `colony build place`, the Blueprint item with its picker, the placement ghost, ghosts of placed builds that cancel on click, the warehouse, farm and cobblestone generator blueprints, bots mining cobblestone at the generator, and new colonies that start only the tool kit and mineshaft are done) |
| M6 | AI architect: blueprints designed outside the game (e.g. by an LLM) in a documented JSON format, imported and validated in-game, then built by the colony |

## Architecture (planned)

- **Bot entity**: custom `PathfinderMob` rendered with a player model and skin. Cheap enough for large colonies, and does not appear in the tab list.
- **Navigation**: custom A* over block costs (walk, dig, place, climb).
- **Task system**: a colony-wide job board (blackboard pattern). Bots are generalists: each picks the job that suits it best by distance, what it carries and priority. Jobs can spawn sub-jobs.
- **Colony**: storage, member list, and a goal queue, persisted as world saved data.
- **Planner**: players place blueprints; a blueprint's role (storage, farm, generator) and its chest and work spots tell the colony how to use the building once it stands. The client draws placement and build ghosts from what the server sends it.
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
