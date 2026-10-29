# `reachable`

```text
reachable(pos, near=0)
```

Whether the bot could walk from where it stands to within `near` blocks of `pos`. It plans the walk without taking it.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | where to go |
| `near` | int | `0` | how close is close enough, in blocks along x, y and z added up, as for `goto`; less than 0 stops the script with an error |

## Returns

`True` if the plan finds a whole way there, `False` if not.

## Pauses

Only when the server's pathfinding budget of 2000 nodes a tick, which every bot's walking shares, runs out before the plan is done: the plan goes on over the next ticks and the script waits.

## Cost

One instruction for each node the plan searches, besides the call.

## Description

`reachable` plans the path `goto(pos, near)` would take, digging and pillaring as it may with what the bot carries, but over loaded blocks only: it loads no chunks. A `pos` in an unloaded chunk is `False`, and so is one past what a single plan searches (4000 nodes), even where `goto`, which plans on as it walks and loads chunks, might get there. A `pos` in another dimension than the bot's is `False`.

## Examples

Go to the first chest the bot can walk to:

```python
for chest in scan("chest", radius=24):
    if reachable(chest, near=2):
        goto(chest, near=2)
        break
```

## See also

[`goto`](goto.md), [`scan`](../world/scan.md), [`find_nearest`](../world/find_nearest.md), [Budget & pausing](../../language/budget.md).
