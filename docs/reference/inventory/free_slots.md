# `free_slots`

```text
free_slots()
```

How many of the bot's inventory slots are empty.

## Returns

An int from 0 to 27. The hand is not counted.

## Pauses

No, it is done at once.

## Cost

20 instructions besides the call.

## Description

A slot with a stack that is not full counts as taken, though more of the same item still fits in it. A script that gathers items can empty its inventory into a chest once this reaches 0.

## Examples

```python
if free_slots() == 0:
    put(memory["chest"], "cobblestone")
```

## See also

[`inventory`](inventory.md), [`put`](../crafting/put.md), [`drop`](drop.md).
