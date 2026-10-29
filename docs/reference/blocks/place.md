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

The first carried block item `item` selects goes to the bot's hand and is placed through the game's own code with a stand-in player at the bot, so the block behaves as when a player places it. Without `facing` or `half`, the block turns as it would for that player.

Only air or a block that may be replaced (grass, water) is placed in, so a slab placed onto a slab is `blocked`, not made a double slab. A `facing` or `half` that is not one of the names above stops the script with an error.

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
| `blocked` | a block that cannot be replaced is there, an entity stands there, or the block cannot stay there |
| `out_of_reach` | the bot could not walk into reach |
| `not_loaded` | the spot is in a chunk that is not loaded |
| `wrong_dimension` | `pos` is in another dimension than the bot's |
| `timeout` | the walk got stuck |

## See also

[`mine`](mine.md), [`use`](use.md), [`build`](../building/build.md), [`block`](../world/block.md).
