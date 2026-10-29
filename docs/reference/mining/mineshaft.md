# `mineshaft`

```text
mineshaft(levels, near=None, branches=4, length=16)
```

Finds where a ladder shaft down to the heights `levels` can be dug, nearest first beside `near`, and gives its plan.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `levels` | list or tuple of int | | the heights to mine at, such as `[16, -54]` |
| `near` | [`Pos`](../types/pos.md) or tuple | the bot's position | where to look from |
| `branches` | int | `4` | branches each side of a hall, 1 to 1024 |
| `length` | int | `16` | how long a branch is, 1 to 1024 |

## Returns

A dict of plain data, the shaft's plan, or `None` where no spot is diggable or `near` is in another dimension than the bot's:

- `top`: the `Pos` the column starts under.
- `hatch`: where the trapdoor goes, in the ground over the top ladder.
- `levels`: the heights kept, deepest last.
- `level`: a dict of height to a dict with `landing`, `chest`, `hall` (a list of `Pos` from the landing outwards), `branches` (the first round of branches off the hall, each a list of `Pos`, feet and head) and `torches` (the torch spots of the hall and those branches).
- `ladders`: a list of `[pos, facing]`, top first.
- `torches`: a list of `Pos`.

The positions carry the bot's dimension. It is the same dict [`mineshaft_at`](mineshaft_at.md) gives for `top` and the kept `levels`.

## Pauses

Only when the search cannot finish this tick: then it goes on over the next ticks and the script waits. A [reflex](../../features/bots.md#reflexes) that takes over while it waits pauses the search, which goes on once the reflex lets go: unlike an action, `mineshaft` never ends with `interrupted`, and always gives the dict or `None`.

## Cost

One instruction per 8 blocks read. The search shares the server-wide block budget with [`scan`](../world/scan.md) and [`find_nearest`](../world/find_nearest.md), and a site found counts 700 blocks of that budget for its dict. A spot where every level fits reads some 3,000 blocks with the default levels; a search where no spot fits reads up to some 300,000.

## Description

The top goes on the ground of its column nearest `near`'s height, at most 16 blocks up or down; a spot without ground in reach is skipped. A spot is diggable when the column runs into no fluid, bedrock or unloaded block down to the first level, nor does a hall, and neither digs next to a nether portal. Levels at or under bedrock in the column, above the top, or less than 2 under the level above are left out of `levels`.

It reads loaded blocks only, and digs nothing: `lib/shaft.py` digs the shaft and `lib/mine.py` mines its branches (see [Mining](../../features/mining.md)). Keep `top` and `levels` in [`memory`](../types/memory.md) and get the same plan back later with `mineshaft_at`, which does not search.

A `levels` that is no list of ints, or `branches` or `length` out of range, stops the script with an error.

## Examples

```python
m = mineshaft([16, -54])
if m is not None:
    memory["shaft"] = {"top": m["top"], "levels": m["levels"]}
    say("shaft at " + str(m["top"]))
else:
    say("no spot for a shaft here")
```

## See also

[`mineshaft_at`](mineshaft_at.md), [`ore_vein`](ore_vein.md), [Mining](../../features/mining.md), [`memory`](../types/memory.md).
