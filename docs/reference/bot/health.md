# `health`

```text
health()
```

The bot's health now, a float.

## Returns

The bot's health, from `0.0` to [`max_health()`](max_health.md) (`20.0`): each point is half a heart.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

A script that keeps an eye on its health can retreat, eat or stop its work before the flee reflex takes over at `botFleeHealth` (6 of 20 by default, see [Reflexes](../../features/bots.md#reflexes)).

## Examples

```python
if health() < max_health() / 2:
    say("hurt, going home")
    goto(home())
```

## See also

[`max_health`](max_health.md), [`attack`](../combat/attack.md), [`deaths`](deaths.md).
