# `held`

```text
held()
```

The stack in the bot's hand.

## Returns

A [`Stack`](../types/stack.md), or `None` when the hand is empty.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

The held stack is also the first one [`inventory`](inventory.md) lists, and [`count`](count.md) and [`has`](has.md) count it. Most actions pick what to hold themselves (`mine` the best tool carried, `place` the block), so what the bot holds changes as it works; [`equip`](equip.md) holds something on purpose.

## Examples

```python
h = held()
if h is None or not matches(h, "#minecraft:swords"):
    equip("#minecraft:swords")
```

## See also

[`equip`](equip.md), [`inventory`](inventory.md), [`matches`](matches.md).
