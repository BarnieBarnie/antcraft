# `container`

```text
container(pos)
```

What a chest, barrel, furnace or other container holds.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | the container |

## Returns

A list of [`Stack`](../types/stack.md) values, one per non-empty slot in slot order, or `None` when there is no container at `pos`, its chunk is not loaded, or `pos` is in another dimension than the bot's.

## Pauses

No, it is done at once.

## Cost

30 instructions besides the call.

## Description

It reads any block that holds items as a hopper sees it: a double chest as a whole, a furnace's input, fuel and output slots. The Colony Core stores items like a chest, so `container(colony()["core"])` reads the colony's stock. The bot need not be near: the container is read from wherever the bot is, as long as its chunk is loaded. The stacks are a snapshot.

## Examples

```python
stock = container(memory["chest"])
if stock is not None:
    logs = sum([s.count for s in stock if matches(s, "#minecraft:logs")])
    print("logs in the chest:", logs)
```

## See also

[`take`](take.md), [`put`](put.md), [`matches`](../inventory/matches.md), [`colony`](../index.md#colony-and-jobs).
