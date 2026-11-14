# `biome`

```text
biome(pos=None)
```

The id of the biome at a position, such as `"minecraft:plains"`.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md), tuple or `None` | `None` | where to look; `None` is the bot's feet |

## Returns

The biome's id as a string, such as `"minecraft:desert"` or `"minecraft:deep_dark"`, or `None` where `pos` is not loaded or is in another dimension than the bot's.

## Pauses

No, it is done at once.

## Cost

30 instructions besides the call.

## Description

`biome` reads the biome the game has for the spot, as the debug screen shows it. It loads no chunks, so it tells nothing of land no player or bot is near, the way `/locate` would. Biomes change with height too: the `y` of `pos` counts, so a cave below a plains can be `"minecraft:lush_caves"`.

## Examples

Say when the bot walks into a desert:

```python
if biome() == "minecraft:desert":
    say("sand all around")
```

## See also

[`biome_tags`](biome_tags.md), [`structures`](structures.md), [`surface`](surface.md), [`block`](block.md).
