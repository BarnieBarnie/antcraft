# `ore_vein`

```text
ore_vein(pos)
```

The blocks of the ore vein at `pos`.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | a block of the vein |

## Returns

A list of `Pos`: `pos` first, then the blocks of the same ore that touch the vein face to face, at most 32 in all, each after one of them it touches. `[]` where `pos` is no ore or is in another dimension than the bot's.

## Pauses

No, it is done at once.

## Cost

10 instructions besides the call, and one per 8 blocks read. Unlike [`mineshaft`](mineshaft.md) it does not draw on the server-wide block budget, as a vein is small.

## Description

An ore is a block of the `c:ores` tag, or one whose drops the bot's pickaxe tiers know. Blocks count as the same ore when they drop the same, so deepslate iron ore is part of an iron vein. It reads loaded blocks only; an unloaded block counts as air.

Mining the blocks in the order given keeps each next to one already open, which is how `lib/mine.py` mines the veins its branches uncover (see [Mining](../../features/mining.md)).

## Examples

```python
for p in ore_vein((10, -40, 5)):
    if not mine(p):
        break
```

## See also

[`mineshaft`](mineshaft.md), [`mine`](../index.md#working-blocks), [`find_nearest`](../index.md#the-world-around), [Mining](../../features/mining.md).
