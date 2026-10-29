# `find_nearest`

```text
find_nearest(what, radius=32)
```

The nearest block that `what` selects, within `radius` blocks of the bot.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `what` | [selector](../types/selectors.md) | | the blocks to look for, such as `"oak_log"` or `"#minecraft:logs"` |
| `radius` | int | `32` | how far to look, from 0 to 32; another number stops the script with an error |

## Returns

The [`Pos`](../types/pos.md) of the nearest matching block, or `None` if there is none within `radius`. The position has no `dimension`: it is in the bot's.

## Pauses

Only when the search cannot finish this tick: it shares a server-wide budget of 32768 blocks read a tick with every other block search (`scan`, `mineshaft`), and goes on over the next ticks while the script waits. A full search of radius 32 reads some 137,000 blocks.

## Cost

One instruction per 8 blocks read, besides the call.

## Description

The search reads the blocks within `radius` (a sphere around the block the bot stands in), nearest first, ties by y, then x, then z, and stops at the first match. It reads loaded blocks only and loads no chunks, so a block in an unloaded chunk is not found. It is [`scan`](scan.md) with a `limit` of 1.

A malformed selector, or a name or tag the game does not know, stops the script with an error.

## Examples

```python
log = find_nearest("#minecraft:logs")
if log is None:
    say("no trees around")
else:
    mine(log)
```

## See also

[`scan`](scan.md), [`block`](block.md), [`reachable`](../movement/reachable.md), [Selectors](../types/selectors.md), [Budget & pausing](../../language/budget.md).
