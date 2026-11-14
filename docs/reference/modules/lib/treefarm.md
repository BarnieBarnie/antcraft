# `lib/treefarm`

Tree cells for a wood farm, one tree each, shaped by the sapling kind: built, watched, harvested and replanted. A module of the mod's default library: `import lib.treefarm`, then `lib.treefarm.build_cell("minecraft:birch_sapling", low, high)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/treefarm.py`, a kit over [`lib/terrain`](terrain.md), [`lib/area`](area.md) and [`lib/trees`](trees.md) (see [The script library](../../../design/library.md#layers)). Its cells come from [`lib/cells`](cells.md): `low` and `high` are the corners of a cell's floor, one block high.

It assumes the bot carries what [`materials`](#materialskind) lists, the tools to clear the cell (a pickaxe where the sapling spot is on stone) and, for a roofed cell, scaffold blocks to pillar up to the roof with; [`lib/get`](get.md) gets those.

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

A spec per sapling, a dict with:

| Key | Meaning |
|---|---|
| `size` | the cell's width and depth, so a tree's logs stay in its cell |
| `square` | a 2x2 trunk, grown from four saplings |
| `clear` | the blocks of air the sapling needs over the floor: the tallest trunk of its tree plus 2, as the game's free-height check of a growing tree wants |
| `roof` | a 3x3 roof of bottom slabs right over the clear blocks, round the trunk |

The values come from the game's tree features, named in the module's comments:

| Sapling | Size | 2x2 | Clear | Roof | Why |
|---|---|---|---|---|---|
| `oak_sapling` | 7 | no | 8 | yes | a trunk of 4 to 6; one in ten grows a fancy oak, whose trunk of up to 14 the roof clips to 6, its branches some 3 out |
| `birch_sapling` | 5 | no | 9 | no | a trunk of 5 to 7, leaves 2 out; a sapling never grows a tall birch |
| `spruce_sapling` | 7 | no | 10 | no | a trunk of 5 to 8, leaves up to 3 out; one sapling a cell, so never a giant |
| `jungle_sapling` | 5 | no | 14 | no | a trunk of 4 to 12, leaves 2 out; one sapling a cell, so never a giant |
| `dark_oak_sapling` | 8 | yes | 11 | no | a 2x2 trunk of 6 to 9, leaning up to 2 and branching 1 out |

A tree grows only where the air over its sapling is free up to its trunk height plus 2; a roof lower than that stops it, but a fancy oak grows shorter instead, down to a trunk of 4. Mobs do not spawn on leaves nor on bottom slabs; under the canopy the torches keep the floor lit.

## `materials(kind)`

The items one cell of `kind` takes, item to count: a dirt and a sapling per sapling spot, 2 torches and, for a roofed kind, 9 `#minecraft:wooden_slabs`. Done at once. The fill to level an uneven cell is not in it: [`lib.terrain.needs`](terrain.md) gives that.

```python
print(lib.treefarm.materials("minecraft:oak_sapling"))
# {'minecraft:dirt': 1, 'minecraft:oak_sapling': 1, 'minecraft:torch': 2, '#minecraft:wooden_slabs': 9}
```

## `build_cell(kind, low, high)`

Builds the cell: levels the floor at `low.y` and clears the `clear` blocks over it ([`lib.terrain.flatten`](terrain.md)), puts dirt under each sapling spot in the cell's middle where the ground is no dirt, a torch on the floor at the `low` and `high` corners (torch light reaches every floor block of a cell up to 8 wide), the roof where the spec has one (taking back the scaffold it pillared up with), then plants.

- **Returns** a [`result()`](result.md): ok, or the reason of the step that failed with `at`.
- **Pauses** yes.

## `grown(kind, low, high)`

Whether the cell's tree has grown: a log at its sapling spot. Done at once.

## `harvest(kind, low, high)`

Fells the cell's tree whole and replants it with [`lib.trees.fell_whole`](trees.md), which picks up the logs and saplings, then picks up the sticks and apples lying about, and a torch of the cell a pillar took the place of, and takes back the blocks it pillared up to drops on the leaves with (`lib.trees.take_back`). The [wood farm](../../../cookbook/wood-farm.md) runs on it.

- **Returns** `fell_whole`'s [`result()`](result.md).
- **Pauses** yes.

## See also

[`lib/cells`](cells.md), [`lib/trees`](trees.md), [`lib/terrain`](terrain.md), [The script library](../../../design/library.md).
