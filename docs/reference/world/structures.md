# `structures`

```text
structures(radius=64)
```

The structures around the bot, such as villages, mineshafts and desert temples, nearest first.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `radius` | int | `64` | how far to look, from 0 to 128; another number stops the script with an error |

## Returns

A list of dicts, nearest first; empty if there are none. Each has:

| Key | Type | Meaning |
|---|---|---|
| `"id"` | str | the structure's id, such as `"minecraft:village_plains"` or `"minecraft:mineshaft"` |
| `"at"` | [`Pos`](../types/pos.md) | the centre of its box |
| `"low"` | [`Pos`](../types/pos.md) | the corner of its box with the lowest `x`, `y` and `z` |
| `"high"` | [`Pos`](../types/pos.md) | the corner with the highest |

The positions are in the bot's dimension.

## Pauses

No, it is done at once.

## Cost

60 instructions besides the call.

## Description

A structure is listed when the box around its pieces (the houses and paths of a village, the tunnels of a mineshaft) comes within `radius` blocks of the bot, measured in a straight line to the nearest block of the box. Only loaded chunks count: both the chunk the structure started in and a chunk within `radius` that it reaches into must be loaded. Like [`biome`](biome.md), `structures` loads nothing and tells nothing of land nobody is near, the way `/locate` would; to find a village, a bot has to go looking.

The box is that of the structure as the world generated it: blocks broken or built since do not change it.

## Examples

Report the villages near the bot:

```python
for s in structures():
    if s["id"].startswith("minecraft:village"):
        say("a village at " + str(s["at"]))
```

## See also

[`biome`](biome.md), [`scan`](scan.md), [`find_nearest`](find_nearest.md).
