# `wait`

```text
wait(ticks)
```

Action: does nothing for `ticks` ticks.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `ticks` | int | | how long to wait, in game ticks (20 a second) |

## Returns

A [`Result`](../types/result.md), ok once the ticks have passed.

## Pauses

Yes: the script goes on `ticks` ticks after the call. A `ticks` of 0 or less returns ok at once, without pausing.

## Cost

One instruction, the call itself.

## Description

The way to let time pass, such as for crops to grow or a furnace to burn, without spending the script's instruction budget on a busy loop. The bot's reflexes run while it waits, and one that takes over ends the wait early. A bot saved while it waits goes on with the ticks it had left when the world loads.

A `ticks` that is no int, such as `1.5`, stops the script with an error.

## Examples

```python
while not is_day():
    wait(200)
```

## Fails with

| Reason | When |
|---|---|
| `interrupted` | a reflex took over before the ticks had passed (see [Reflexes](../../features/bots.md#reflexes)) |

## See also

[`stop`](stop.md), [`time`](../world/time.md), [Budget & pausing](../../language/budget.md), [Pausing](../../features/scripts.md#pausing).
