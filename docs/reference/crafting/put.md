# `put`

```text
put(container, item, n=None)
```

Walks into reach of a container and puts carried items into it.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `container` | [`Pos`](../types/pos.md) or tuple | | a chest, barrel, furnace, the Colony Core or another container |
| `item` | [selector](../types/selectors.md) | | the items to put in |
| `n` | int | `None` | how many at most; `None` puts in every matching item |

## Returns

A [`Result`](../types/result.md); its `value` is how many items went in.

## Pauses

Yes: it walks, as [`goto`](../movement/goto.md) does, to where the bot sees the container within 4 blocks of its eyes, and moves the items in the tick it gets there.

## Cost

One instruction, the call itself. The walk plans its path as `goto` does.

## Description

It moves items from the bot's hand and inventory into the container, from the inventory slots first and the hand last, each into the first slot of the container it fits in. A double chest takes items in both halves. An `n` below 1, a malformed selector, or a name or tag the game does not know stops the script with an error.

## Examples

```python
core = colony()["core"]
put(core, "#minecraft:logs")
put(core, "cobblestone", 32)
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | the bot carries nothing `item` selects |
| `target_gone` | there is no container at `container` |
| `no_space` | the container filled up; `value` is how many went in |
| `protected` | the container may not be changed: outside the build height or border, or under spawn protection |
| `out_of_reach` | the bot could not walk into reach |
| `not_loaded` | the container is in a chunk that is not loaded |
| `wrong_dimension` | `container` is in another dimension than the bot's |
| `timeout` | the walk got stuck |

## See also

[`take`](take.md), [`container`](container.md), [`drop`](../inventory/drop.md), [`free_slots`](../inventory/free_slots.md).
