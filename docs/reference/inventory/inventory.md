# `inventory`

```text
inventory()
```

The stacks the bot carries.

## Returns

A list of [`Stack`](../types/stack.md) values: the stack in the bot's hand first, if it holds one, then the non-empty slots of its inventory in slot order. Empty slots are left out, so a bot that carries nothing gets `[]`.

## Pauses

No, it is done at once.

## Cost

30 instructions besides the call.

## Description

The stacks are a snapshot: they do not change when the bot picks up, places or drops items later. The bot has 27 inventory slots besides its hand. Two stacks of the same item are listed apart; [`count`](count.md) adds them up.

## Examples

```python
for s in inventory():
    print(s.item, s.count)
```

## See also

[`held`](held.md), [`count`](count.md), [`has`](has.md), [`free_slots`](free_slots.md), [`Stack`](../types/stack.md).
