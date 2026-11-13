# `surface`

```text
surface(pos)
```

The surface of the column `pos` is in: the lowest spot with nothing above it that a mob cannot walk through, leaves left out.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | a position in the column; only its `x` and `z` count |

## Returns

A [`Pos`](../types/pos.md) with the `x` and `z` of `pos` and the `y` one above the column's highest solid block or fluid, or `None` where the column is not loaded, `pos` is in another dimension than the bot's, or the bot is in a dimension with a roof over it (the Nether), which has no open sky to come up to.

## Pauses

No, it is done at once.

## Cost

30 instructions besides the call.

## Description

`surface` reads the game's heightmap of the column, which is up to date with every block placed or broken; it loads no chunks. Whether a spot is under the open sky is `p.y >= surface(p).y`: a bot at the bottom of a tall cave is not, as the cave's roof is above it. Leaves do not count, so a spot under a tree is at the surface; water and lava do, so the surface of a lake is on top of its water. In the Nether the bedrock roof is over every column, so `surface` gives `None` there: [`lib.dig.stairs_up`](../modules/lib/dig.md) then gives `no_surface` instead of digging up into the roof.

## Examples

How deep the bot is under the surface:

```python
here = pos()
print("blocks to dig up:", surface(here).y - here.y)
```

## See also

[`block`](block.md), [`lib.dig.stairs_up`](../modules/lib/dig.md), [`Pos`](../types/pos.md).
