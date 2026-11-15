# `food`

```text
food()
```

The bot's food level now, an int.

## Returns

The bot's food level, from `0` to `20`, as a player's hunger bar: each point is half a drumstick.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

Work uses up food as it does a player's, and a bot heals only at 18 food or more and starves at 0 (see [Hunger](../../features/bots.md#hunger)). [`saturation()`](saturation.md) goes first: food drops only once it is used up. [`eat`](../inventory/eat.md) fills it again.

## Examples

```python
if food() < 18 and has("bread"):
    eat("bread")
```

## See also

[`saturation`](saturation.md), [`eat`](../inventory/eat.md), [`health`](health.md).
