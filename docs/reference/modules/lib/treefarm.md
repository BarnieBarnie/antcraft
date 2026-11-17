# `lib/treefarm`

Tree cells for a wood farm, one tree each, shaped by the sapling kind: built, watched, harvested and replanted. A module of the mod's default library: `import lib.treefarm`, then `lib.treefarm.build_cell("minecraft:birch_sapling", low, high)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/treefarm.py`, a kit over [`lib/terrain`](terrain.md), [`lib/area`](area.md) and [`lib/trees`](trees.md) (see [The script library](../../../design/library.md#layers)). Its cells come from [`lib/cells`](cells.md): `low` and `high` are the corners of a cell's floor, one block high.

It assumes the bot carries what [`materials`](#materialskind) lists, the tools to clear the cell (a pickaxe where the sapling spot is on stone) and the fill to level it; [`lib/get`](get.md) gets those, and `harvest` gets the saplings it lacks to replant with.

```python
import lib.cells
import lib.treefarm

kind = "minecraft:birch_sapling"
size = lib.treefarm.KINDS[kind]["size"]
lib.cells.layout("wood", Pos(100, 64, 200), (size, size))
i = lib.cells.next("wood")
low, high = lib.cells.cell("wood", i)
if lib.treefarm.build_cell(kind, low, high)["ok"]:
    lib.cells.add("wood", i)
while not lib.treefarm.grown(kind, low, high):
    wait(200)
lib.treefarm.harvest(kind, low, high)
```

## `KINDS`

A spec per kind, a dict with:

| Key | Meaning |
|---|---|
| `sapling` | the item that grows the kind, only where the key is no sapling: a giant kind's (see [`sapling`](#saplingkind)) |
| `size` | the cell's width and depth, so a tree's logs stay in its cell |
| `square` | a 2x2 trunk, grown from four saplings |
| `clear` | the blocks of air the sapling needs over the floor: the tallest trunk of its tree plus 2, as the game's free-height check of a growing tree wants |
| `roof` | a 3x3 roof of bottom slabs right over the clear blocks, round the trunk |

The values come from the game's tree features, named in the module's comments:

| Kind | Size | 2x2 | Clear | Roof | Why |
|---|---|---|---|---|---|
| `oak_sapling` | 7 | no | 8 | yes | a trunk of 4 to 6; one in ten grows a fancy oak, whose trunk of up to 14 the roof clips to 6, its branches some 3 out |
| `birch_sapling` | 5 | no | 9 | no | a trunk of 5 to 7, leaves 2 out; a sapling never grows a tall birch |
| `spruce_sapling` | 7 | no | 10 | no | a trunk of 5 to 8, leaves up to 3 out; one sapling a cell, so never a giant |
| `jungle_sapling` | 5 | no | 14 | no | a trunk of 4 to 12, leaves 2 out; one sapling a cell, so never a giant |
| `dark_oak_sapling` | 8 | yes | 11 | no | a 2x2 trunk of 6 to 9, leaning up to 2 and branching 1 out |
| `pale_oak_sapling` | 8 | yes | 11 | no | grown as a dark oak |
| `acacia_sapling` | 7 | no | 11 | no | a trunk of 5 to 9, leaning up to 3 one way and forking up to 3 another |
| `cherry_sapling` | 11 | no | 10 | no | a trunk of 7 to 8, one to three branches up to 5 out, leaves 4 out of their ends |
| `poplar_sapling` | 7 | no | 13 | no | a trunk of 7 to 11 with logs in its canopy, up to 3 out of the trunk (measured over 200 trees), leaves 7 out |
| `giant_spruce` | 10 | yes | 31 | no | four `spruce_sapling`s: a 2x2 trunk of 13 to 29, leaves 4 out |
| `giant_jungle` | 10 | yes | 33 | no | four `jungle_sapling`s: a 2x2 trunk of 10 to 31, branches up to 4 out |
| `mangrove_propagule` | 11 | no | 23 | no | a trunk of 4 to 14 on roots 3 to 7 (15 in 100 grow a trunk of 2 to 7 on roots 1 to 3) over the propagule's spot, branches stepping out and up, logs up to 5 out and roots up to 7 out (measured over 188 trees) |

A kind's key is its sapling, with or without `minecraft:`, but for the giants: they grow from the same saplings as the one-sapling spruce and jungle, four in a square, so they have a name of their own and `sapling` names the item. A mangrove's spec also has `roots`: its trunk stands on roots over its propagule's spot, so `grown` and `harvest` find its lowest log up the spot's column, and `harvest` mines the roots in the cell after the felling (#650). `roots` is how far out of the cell `harvest` mines them too: 2, as roots reach up to 7 out of the trunk, 2 past the cell's edge (#768).

A tree grows only where the air over its sapling is free up to its trunk height plus 2; a roof lower than that stops it, but a fancy oak grows shorter instead, down to a trunk of 4. Mobs do not spawn on leaves nor on bottom slabs; under the canopy the torches keep the floor lit.

## `sapling(kind)`

The item that grows `kind`: the kind itself, or for a giant kind the sapling its spec names. Done at once.

```python
print(lib.treefarm.sapling("birch_sapling"), lib.treefarm.sapling("giant_spruce"))
# minecraft:birch_sapling minecraft:spruce_sapling
```

## `materials(kind)`

The items one cell of `kind` takes, item to count: a dirt and a sapling per sapling spot, 2 torches and, for a roofed kind, 9 `#minecraft:wooden_slabs` and `clear` more dirt to pillar up to the roof with, which `build_cell` takes back. Done at once. The fill to level an uneven cell depends on its ground, so it is not in it: [`lib.terrain.needs`](terrain.md) gives that.

```python
print(lib.treefarm.materials("minecraft:oak_sapling"))
# {'minecraft:dirt': 9, 'minecraft:oak_sapling': 1, 'minecraft:torch': 2, '#minecraft:wooden_slabs': 9}
```

## `build_cell(kind, low, high)`

Builds the cell: levels the floor at `low.y` and clears the `clear` blocks over it ([`lib.terrain.flatten`](terrain.md)), puts dirt under each sapling spot in the cell's middle where the ground is not `SOIL` (`#minecraft:substrate_overworld`: dirt, grass, podzol, mud or moss, which a sapling grows on, is left as it is; #805) (`soil`), a torch on the floor at the `low` and `high` corners where no torch, standing or on a wall, is there already (`flatten` keeps one there, so a build tried again leaves them; #760) (torch light reaches every floor block of each kind's cell, the cherry's 11 wide too, under its grown tree), the roof where the spec has one (taking back the scaffold it pillared up with), then plants.

- **Returns** a [`result()`](result.md): ok, or the reason of the step that failed with `at`.
- **Pauses** yes.

## `grown(kind, low, high)`

Whether the cell has a tree to fell: a log in its tree space, the cell up to `clear` blocks over the floor, read with one [`scan`](../../world/scan.md) round it: at its sapling spot, one a failed `harvest` left in the column over a sapling spot, as a sapling under it never grows, or one off those columns, such as an acacia's or cherry's branch. Done at once, or over a few ticks where the server's block search budget runs short.

## `loaded(kind, low, high)`

Whether the bot can read the cell: the blocks of its sapling spots and torches are loaded, so [`block`](../../world/block.md) gives no `None` there. A bot keeps the chunks within 3 of its own loaded, so a cell farther off may not be; the wood farm handles a cell only once it is (#810). Done at once.

## `harvest(kind, low, high, radius=32, replant=True)`

Fells the cell's tree whole from the lowest log `grown` sees, the lowest over a sapling spot first, with [`lib.trees.fell_whole`](trees.md), which picks up the logs and saplings, again while `grown` still sees a log, such as one a bot that died up the tree left cut off from it, having mined the logs round it (at most 4 fellings, #713), then picks up the sticks and apples, and a torch of the cell a pillar took the place of, that lie on the floor or a step over it, and takes back the blocks it pillared with (`lib.trees.take_back`). Before the pick-up, for a kind with `roots` (the mangrove), it mines the roots in the cell's tree space, the highest first: one may stand on the propagule's spot, and each tree grows more round the old ones (mine puts the roots it drops into the inventory, and the wood farm stores them, #751). It also mines those up to `roots` blocks out of the cell, but not in the columns of another cell of a farm in `memory["cells"]` (`outside_cells()`), whose own harvest mines them: in 4 of 23 seeded mangroves 2 to 7 roots reached out of the cell, and at the farm's edge no harvest mined them (#768). It neither pillars nor digs up to a drop on the leaves: the leaves of a felled tree go on dropping sticks and apples as they decay, and chasing each one took most of a dark oak's harvest, so the wood farm's sweep picks those up before its waits (#655). Then, where it felled the tree whole, it plants a sapling on each sapling spot that is air, with [`plant`](#plantkind-p-radius32), so with dirt put back under it where a path dug it out (#764): last, as a pillar up to the leaves may take a sapling's place (#652). The saplings it lacks for those spots, such as a 2x2 kind with fewer than four on the bot, it gets within `radius` with [`lib.get`](get.md); where it cannot, the spots stay empty for the wood farm's tending. With `replant=False` it plants nothing, for a cell given up, as the small cells a wood farm moved to giants from (#807). Its tree's top logs may stand past its reach from the ground, as a dark oak's do, so the bot carries blocks to pillar with, as the wood farm's 16 dirt. The [wood farm](../../../cookbook/wood-farm.md) runs on it.

- **Returns** `fell_whole`'s [`result()`](result.md): ok (also where no log stands), or `out_of_reach` or `no_space` with `at`, the log it left, which `grown` still sees, so the next harvest fells it.
- **Pauses** yes.

## `plant(kind, p, radius=32)`

Plants a sapling of `kind` at the sapling spot `p`, the bot carrying one: first puts dirt under it where the ground is not `SOIL` (`soil(ground, radius=32)`), as a path may dig the dirt out or put a block there (#764), mining what is there (with a pickaxe it gets or takes from a chest where it has none, such as for stone; #767) and getting a dirt it lacks within `radius` with `get_outside(item, n, radius=32)`: [`lib.get`](get.md), but never from under a cell of a farm in `memory["cells"]` ([`lib.cells`](cells.md)), built or next to build (`outside_cells()`, which narrows [`lib.find.WHERE`](find.md) for that get; the wood farm gets its dirt so too, #768), then mines what stands at `p` and places the sapling. `build_cell`, `harvest` and the wood farm's tending plant with it.

- **Returns** a [`result()`](result.md): ok, or the reason of the step that failed with `at`.

## See also

[`lib/cells`](cells.md), [`lib/trees`](trees.md), [`lib/terrain`](terrain.md), [The script library](../../../design/library.md).
