# `mineshaft_at`

```text
mineshaft_at(top, levels, round=0, branches=4, length=16)
```

The plan of a shaft already started at `top`, without looking at the world.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `top` | [`Pos`](../types/pos.md) or tuple | | the top of the shaft, as [`mineshaft`](mineshaft.md) gave it |
| `levels` | list or tuple of int | | its heights, as `mineshaft` kept them |
| `round` | int | `0` | which round of branches the plan lists, 0 to 1024 |
| `branches` | int | `4` | branches each side of a hall, 1 to 1024 |
| `length` | int | `16` | how long a branch is, 1 to 1024 |

## Returns

The dict [`mineshaft`](mineshaft.md) describes (`top`, `hatch`, `levels`, `level`, `ladders`, `torches`), with each level's `branches` and `torches` those of round `round`; `None` when `top` is in another dimension than the bot's.

## Pauses

No, it is done at once.

## Cost

1500 instructions besides the call.

## Description

It reads no blocks: the plan follows from `top`, `levels`, `branches` and `length` alone, so a script that kept `top` and `levels` in [`memory`](../types/memory.md) gets its shaft back after a restart, the same plan `mineshaft` gave. Give it the `branches` and `length` the search had, as they shape the halls.

Branches are 3 blocks apart, so a hall is 3 times `branches` long. Round `n` carries each branch on where round `n - 1` ends, so a miner that has mined out round 0 asks for round 1 to go further.

A `levels` that is no list of ints, or `round`, `branches` or `length` out of range, stops the script with an error.

## Examples

```python
kept = memory.get("shaft")
if kept is not None:
    m = mineshaft_at(kept["top"], kept["levels"], round=1)
    for branch in m["level"][kept["levels"][0]]["branches"]:
        print(branch[0], "to", branch[-1])
```

## See also

[`mineshaft`](mineshaft.md), [`ore_vein`](ore_vein.md), [Mining](../../features/mining.md).
