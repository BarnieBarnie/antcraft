# `scan`

```text
scan(what, radius=16, limit=64)
```

The positions of the blocks that `what` selects within `radius` blocks of the bot, nearest first.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `what` | [selector](../types/selectors.md) | | the blocks to look for, such as `"chest"` or `["iron_ore", "deepslate_iron_ore"]` |
| `radius` | int | `16` | how far to look, from 0 to 32; another number stops the script with an error |
| `limit` | int | `64` | how many to find at most; less than 0 stops the script with an error |

## Returns

A list of [`Pos`](../types/pos.md), nearest first, at most `limit` long; empty if none match. The positions have no `dimension`: they are in the bot's.

## Pauses

Only when the search cannot finish this tick: it shares a server-wide budget of 32768 blocks read a tick with every other block search (`find_nearest`, `mineshaft`), and goes on over the next ticks while the script waits. A search of radius 16 reads about 17,000 blocks, one of radius 32 about 137,000.

## Cost

One instruction per 8 blocks read, besides the call.

## Description

The search reads the blocks within `radius` (a sphere around the block the bot stands in), nearest first, ties by y, then x, then z, so the order is always the same, and stops once `limit` matched. It reads loaded blocks only and loads no chunks. A small `limit` makes a search that finds enough stop early, and read less.

A malformed selector, or a name or tag the game does not know, stops the script with an error.

## Examples

Count the chests around:

```python
chests = scan(["chest", "barrel"], radius=24)
print(len(chests), "chests and barrels")
```

## See also

[`find_nearest`](find_nearest.md), [`block`](block.md), [`reachable`](../movement/reachable.md), [Selectors](../types/selectors.md), [Budget & pausing](../../language/budget.md).
