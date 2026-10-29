# `entities`

```text
entities(what=None, radius=16)
```

The mobs, players, dropped items and other entities around the bot, nearest first.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `what` | entity [selector](../types/selectors.md) or `None` | `None` | the entity types to list, such as `"cow"` or a tag of entity types; `None` lists every kind |
| `radius` | int | `16` | how far to look, from 0 to 64; another number stops the script with an error |

## Returns

A list of [`Entity`](../types/entity.md), nearest first; empty if there are none.

## Pauses

No, it is done at once.

## Cost

120 instructions besides the call.

## Description

The distance is measured in a straight line from the bot. The bot itself is left out, and so are entities that are dead. An `Entity` is read live: its `pos` and `health` are where it is and how it is when the script reads them, and `valid` turns false once it is gone. Dropped items are entities too; [`items_near`](items_near.md) lists those alone, by the item they carry.

The selector names entity types, not items or blocks: a name or tag the game does not know as an entity type stops the script with an error.

## Examples

```python
cows = entities("cow", radius=24)
if cows:
    goto(cows[0].pos, near=2)
```

## See also

[`items_near`](items_near.md), [`Entity`](../types/entity.md), [Selectors](../types/selectors.md).
