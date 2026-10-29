# `dimension`

```text
dimension()
```

The id of the dimension the bot is in, such as `"minecraft:overworld"` or `"minecraft:the_nether"`.

## Returns

A string, the same as `pos().dimension`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

Every function that takes a position refuses one whose `dimension` is not this one (see [Dimension](../types/pos.md#dimension)), so a script that may be in another dimension compares first.

## Examples

```python
if dimension() == "minecraft:the_nether":
    gold = find_nearest("nether_gold_ore")
```

## See also

[`home_dimension`](home_dimension.md), [`pos`](pos.md), [`travel`](travel.md), [`portals`](portals.md).
