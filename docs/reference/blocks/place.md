# `place`

```text
place(pos, item, facing=None, half=None)
```

Walks into reach of a spot, holds a block item and places it there.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | where the block goes |
| `item` | [selector](../types/selectors.md) | | the block item to place, such as `"oak_planks"` or `"#minecraft:planks"` |
| `facing` | str | `None` | which way the block faces: `"north"`, `"south"`, `"east"`, `"west"`, `"up"` or `"down"`, for a block that can face that way |
| `half` | str | `None` | `"top"` or `"bottom"`, for a block with a half (a trapdoor, stairs, a slab) |

## Returns

A [`Result`](../types/result.md), ok once the block is placed.

## Pauses

Yes: it walks, as [`goto`](../movement/goto.md) does, to where the bot sees the spot within 4 blocks of its eyes, and places the block in the tick it gets there.

## Cost

One instruction, the call itself. The walk plans its path as `goto` does.

## Description

The first carried block item `item` selects goes to the bot's hand and is placed through the game's own code with a stand-in player at the bot, so the block behaves as when a player places it. Without `facing` or `half`, the block turns as it would for that player. A block that stands or hangs on a wall, such as a torch, a sign or a head, stands on the block under the spot when that block holds it, as when a player clicks that block's top, and goes on a wall beside the spot only where it cannot (#760).

Reach is a matter of sight: the bot sees the spot when a line from its eyes, taken over the middle of the block it stands in wherever in it it stands, reaches the spot's middle or the middle of one of its faces with no other block's outline in the way. Tall grass, flowers, a torch or a cobweb in front hide the spot as a wall does, so the bot walks to where it sees past them; [`mine`](mine.md) and [`use`](use.md) judge reach the same way.

A soft block at the spot (grass, a fern, a flower, a sapling, snow of any depth, leaves, a vine, moss carpet) is broken first, as [`mine`](mine.md) breaks it, its drops going into the inventory, unless it is the block placed and takes another, as snow onto snow; a sapling onto the same sapling breaks it and plants again. Otherwise only air or a block that may be replaced (water) is placed in, so a slab placed onto a slab is `blocked`, not made a double slab. A fluid, fire or a block with contents such as a chest is never broken. A `facing` or `half` that is not one of the names above stops the script with an error.

## Examples

```python
soil = memory["field"]
use(soil, "#minecraft:hoes")
place(soil.up(), "wheat_seeds")
```

```python
place((10, 64, -3), "oak_stairs", facing="east", half="bottom")
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | the bot carries no block item `item` selects |
| `protected` | the spot may not be changed: outside the build height or border, or under spawn protection |
| `blocked` | a block that cannot be replaced and is not soft is there, an entity stands there, or the block cannot stay there; `detail` says which, one of the ones below |
| `out_of_reach` | the bot could not walk to where it sees the spot within reach |
| `not_loaded` | the spot is in a chunk that is not loaded |
| `wrong_dimension` | `pos` is in another dimension than the bot's |
| `timeout` | the walk got stuck |

The `detail` of `blocked`:

| `detail` | When |
|---|---|
| `minecraft:glass at Pos(x, y, z) cannot be replaced` | a block (its id) that cannot be replaced, and is not soft, is at the spot |
| `the game would not place minecraft:dirt at Pos(x, y, z)` | the game refused to place the item (its id) there, such as where an entity stands or where the block cannot stay |

## See also

[`mine`](mine.md), [`use`](use.md), [`build`](../building/build.md), [`block`](../world/block.md).
