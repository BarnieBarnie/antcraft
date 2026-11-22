# `take`

```text
take(container, item, n=None)
```

Walks into reach of a container and takes items out of it.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `container` | [`Pos`](../types/pos.md) or tuple | | a chest, barrel, furnace, the Colony Core or another container |
| `item` | [selector](../types/selectors.md) or [`Stack`](../types/stack.md) | | the items to take, or one stack from [`container`](container.md) |
| `n` | int | `None` | how many at most; `None` takes every matching item |

## Returns

A [`Result`](../types/result.md); its `value` is how many items it took.

## Pauses

Yes: it walks, as [`goto`](../movement/goto.md) does, to where the bot sees the container within 4 blocks of its eyes, and moves the items in the tick it gets there.

## Cost

One instruction, the call itself. The walk plans its path as `goto` does.

## Description

It moves items from the container into the bot's inventory, each into the first slot it fits in; the hand takes items only when the inventory has no room. Given a [`Stack`](../types/stack.md), it takes the stacks of the same item, damage and enchantments (the count may differ), so a script takes the one rod it picked from `container` and leaves the others (#997). An `n` below 1, a malformed selector, or a name or tag the game does not know stops the script with an error.

## Examples

```python
core = colony()["core"]
if not has("#minecraft:pickaxes"):
    r = take(core, "#minecraft:pickaxes", 1)
    if not r:
        warn("no pickaxe in the core:", r.reason)

rods = [s for s in container(core) if s.item == "minecraft:fishing_rod"]
if rods:
    take(core, max(rods, key=lambda s: len(s.enchantments)))
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | the container holds nothing `item` selects, or no stack equal to the `Stack` |
| `target_gone` | there is no container at `container` |
| `no_space` | the bot's inventory and hand filled up; `value` is how many it took |
| `protected` | the container may not be changed: outside the build height or border, or under spawn protection |
| `out_of_reach` | the bot could not walk into reach |
| `not_loaded` | the container is in a chunk that is not loaded |
| `wrong_dimension` | `container` is in another dimension than the bot's |
| `timeout` | the walk got stuck |

## See also

[`put`](put.md), [`container`](container.md), [`matches`](../inventory/matches.md), [`free_slots`](../inventory/free_slots.md).
