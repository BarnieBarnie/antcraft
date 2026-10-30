# Blueprints

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

Spots are mirrored and turned with the structure, so they stay on the same blocks however it is placed. A file with an unknown role, a missing field or a bad position is left out, with the reason in the server log. A bot builds a blueprint with the script action `build(blueprint, at, turn=0, mirror=False)`, from the blocks it carries, and `blueprint(id)` gives a script its size, role, materials and spots (see the [API reference](../reference/index.md)).

**Litematica schematics.** A schematic saved with [Litematica](https://www.curseforge.com/minecraft/mc-mods/litematica) is a blueprint too: put the `.litematic` file beside the JSON blueprints, as `data/<namespace>/blueprint/<name>.litematic`, and its id is `<namespace>:<name>` as for a JSON file. It shows in the picker and as a ghost, and bots `build` it, like any other blueprint. Its name is the schematic's name; it has no role, chests or work spots. A schematic with several regions is one blueprint, the regions placed as Litematica saved them and its origin at their lowest corner (where regions overlap, the later one's blocks win). Air inside a region is built as air, as in a structure file: a bot clears what stands there. Only block states are read: chest contents, sign text and other block entity data, and entities, are left out. A schematic saved in an older game version has its blocks brought up to date as a structure file's are. Where a JSON blueprint and a schematic have the same id, the JSON blueprint is loaded and the schematic left out, with a warning in the server log; a file that cannot be read is left out, with the reason in the log.

**The Blueprint item** (shapeless: paper and a copper ingot) picks blueprints in game. Right-click with it to open the picker, which lists every blueprint the server has (sent to each client on joining and after `/reload`) with its size, role and the materials it needs; choosing one sets the item to it, and its tooltip names the chosen blueprint.

With a blueprint chosen, holding the item shows a see-through ghost of the structure centred on the block you look at, standing against the face you look at. The list holds no blocks, so it stays small for data packs with many or large structures; the client asks the server for the blocks of the chosen blueprint the first time it shows its ghost, and keeps them until the next `/reload`. A structure too large for one network payload comes in several, which the client puts back together (up to 64 payloads; a larger one shows no ghost), and a request the client could not send is sent again, at most once a second, while the ghost is shown; one the server did not answer within 30 seconds is sent again. The controls, rebindable under AntCraft in the controls menu:

| Control | Does |
|---|---|
| `R` (sneak + `R` the other way), or scroll while sneaking | Turns the ghost a quarter |
| `M` | Mirrors the ghost |

Ghosts are drawn as the blocks themselves, textured and see-through, fully lit so they show at night, without the faces two of their solid blocks share; grass, leaves, vines and other blocks the game colours by biome keep the colour of the biome they stand in, under the ghost's tint; water, and the water of waterlogged blocks, shows as see-through water in the biome's water colour under the tint, drawn as the game draws it: the still texture on its surface, the flowing texture on its sides, each face shaded, and the surface sloping towards lower water of the ghost (the surface keeps the still texture, without a flow direction), without faces between water blocks (lava likewise); any other block with no model shows as an outline. A ghost's blocks and colours are worked out once and kept while it stays the same, not every frame; when a chunk loads within 8 blocks of a ghost, its colours are worked out again, so a ghost shown before the chunk's biomes arrived gets their colours. At most 4096 blocks of a ghost are drawn.
