# `saturation`

```text
saturation()
```

The bot's saturation now, a float.

## Returns

The bot's saturation, from `0.0` to its [`food()`](food.md) level. A bot starts and respawns with `5.0`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

Saturation is used up before food, as a player's, and a bot at 20 food with saturation left heals fast, every 10 ticks (see [Hunger](../../features/bots.md#hunger)). Food that fills more, such as a steak, gives more of it.

## Examples

```python
print("food", food(), "saturation", saturation())
```

## See also

[`food`](food.md), [`eat`](../inventory/eat.md).
