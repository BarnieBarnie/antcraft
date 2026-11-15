# `monsters`

```text
monsters()
```

The hostile mobs the game knows, modded ones included.

## Parameters

None.

## Returns

A list of entity type ids, such as `"minecraft:zombie"`, in the entity type registry's order: every entity type of the monster category. That holds the neutral endermen, zombified piglins and piglins too, the warden and the bosses.

## Pauses

No, it is done at once.

## Cost

20 instructions besides the call. The list is read from the game once, the first time a script asks, and copied after.

## Description

The list is a [selector](../types/selectors.md), so [`entities`](entities.md) takes it as it is. [`jobs.guard`](../../cookbook/guard.md) fights those of it but the neutral ones, the warden and the bosses (#722). It does not look at the bot or the world.

## Examples

```python
near = entities(monsters(), 16)
```

## See also

[`entities`](entities.md), [`attack`](../combat/attack.md).
