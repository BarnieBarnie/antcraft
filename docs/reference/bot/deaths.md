# `deaths`

```text
deaths()
```

How often the bot has died, an int.

## Returns

The number of times the bot died since it was made, `0` for one that never died. It is saved with the bot.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

A bot that dies respawns at its home and runs its script again from the top, so a script can compare `deaths()` with a count it kept in [`memory`](../types/memory.md) to tell it has just respawned, and go back for its items with [`last_death`](last_death.md).

## Examples

```python
if deaths() > memory.get("deaths", 0):
    memory["deaths"] = deaths()
    say("back from the dead")
```

## See also

[`last_death`](last_death.md), [Death and respawn](../../features/death.md).
