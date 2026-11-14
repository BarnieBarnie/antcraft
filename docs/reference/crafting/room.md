# `room`

```text
room(pos, item)
```

How many of an item fit in a chest, barrel or other container.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | the container |
| `item` | string | | one item id, such as `"cobblestone"`; not a tag |

## Returns

An int: the room in empty slots plus the room left in the slots that hold `item`, `0` for a full container. `None` when there is no container at `pos`, its chunk is not loaded, or `pos` is in another dimension than the bot's.

## Pauses

No, it is done at once.

## Cost

30 instructions besides the call.

## Description

It counts as a hopper fills the container: a double chest as a whole, and only the slots that take the item, so a furnace has room for coal in its fuel slot. Like [`container`](container.md) it reads from wherever the bot is, as long as the chunk is loaded, so a script can skip a full chest before walking to it.

## Examples

```python
chest = memory["chest"]
if room(chest, "cobblestone"):
    put(chest, "cobblestone")
else:
    say("the chest is full")
```

## See also

[`container`](container.md), [`put`](put.md), [`free_slots`](../inventory/free_slots.md), [`lib.storage`](../modules/lib/storage.md).
