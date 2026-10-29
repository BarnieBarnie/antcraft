# `items_near`

```text
items_near(item=None, radius=16)
```

The dropped items around the bot, nearest first.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | [selector](../types/selectors.md) or `None` | `None` | the items to list, such as `"wheat"` or `"#minecraft:logs"`; `None` lists all |
| `radius` | int | `16` | how far to look, from 0 to 64; another number stops the script with an error |

## Returns

A list of [`Entity`](../types/entity.md), nearest first; empty if there are none. Each one's `item` is the [`Stack`](../types/stack.md) it carries.

## Pauses

No, it is done at once.

## Cost

10 instructions besides the call.

## Description

The distance is measured in a straight line from the bot. To walk to dropped items and take them is [`pick_up`](../inventory/pick_up.md).

A malformed selector, or a name or tag the game does not know, stops the script with an error.

## Examples

```python
for drop in items_near("#minecraft:saplings", radius=8):
    print(drop.item.count, drop.item.item, "at", drop.pos)
```

## See also

[`entities`](entities.md), [`pick_up`](../inventory/pick_up.md), [`Entity`](../types/entity.md), [`Stack`](../types/stack.md).
