# `block`

```text
block(pos)
```

The block at `pos`, as it is now.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | where to look |

## Returns

A [`Block`](../types/block.md), a snapshot of the block when it was read, or `None` where the block is not loaded or `pos` is in another dimension than the bot's.

## Pauses

No, it is done at once.

## Cost

30 instructions besides the call.

## Description

`block` reads any loaded block, however far from the bot; it loads no chunks. Air is a block too, with `is_air` true. The `Block` does not change when the world does: read it again to see what is there now.

## Examples

Mine the wheat above a field that is ripe:

```python
for soil in scan("farmland", radius=8):
    crop = block(soil.up())
    if crop is not None and crop.id == "minecraft:wheat" and crop.state["age"] == 7:
        mine(soil.up())
```

## See also

[`Block`](../types/block.md), [`find_nearest`](find_nearest.md), [`scan`](scan.md), [`container`](../index.md#containers-and-crafting).
