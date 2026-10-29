# `attack`

```text
attack(entity)
```

Walks to a mob and strikes it with what the bot holds until it dies.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `entity` | [`Entity`](../types/entity.md) | | the mob to fight, as [`entities`](../world/entities.md) gives it |

## Returns

A [`Result`](../types/result.md), ok once the target died.

## Pauses

Yes, until the target is dead, gone or cannot be got to.

## Cost

One instruction, the call itself. Its walks plan paths as [`goto`](../movement/goto.md) does.

## Description

The bot walks into melee reach of the target, as `goto` walks, digging and pillaring where it must, and walks after it when it moves more than two blocks from where the bot was heading. In reach and in sight of it, the bot strikes with what it holds, each blow doing what a player's would with the same item (1 bare-handed, 7 with a diamond sword), as often as a player can with it at full strength: every 5 ticks bare-handed (4 blows a second), every 13 ticks with a sword (1.6 a second) and every 20 to 25 ticks with an axe (1 to 0.8 a second), from the item's attack speed; [`equip`](../inventory/equip.md) a sword first. A blow that kills the target ends the action with ok, also when something else dealt the last blow.

By default the bot attacks only hostile mobs, such as zombies and skeletons, and animals that are neither tamed nor named. Players, other bots, tamed or named animals and anything else, such as villagers or dropped items, are `protected`. A server can allow players, other bots and tamed or named animals as targets with the config keys `botsAttackPlayers`, `botsAttackBots` and `botsAttackTamed` (see [Config](../../features/config.md)). The bot never attacks itself.

A bot at or below `botFleeHealth` with a hostile mob near it flees instead of fighting on: the attack ends with `interrupted` (see [Reflexes](../../features/bots.md#reflexes)). Check [`health()`](../bot/health.md) to stop a fight before that.

An argument that is no `Entity` stops the script with an error.

## Examples

```python
equip("#minecraft:swords")
for mob in entities("zombie", 8):
    r = attack(mob)
    if not r:
        warn("fight ended:", r.reason)
        break
```

## Fails with

| Reason | When |
|---|---|
| `protected` | the target may not be attacked: a player, another bot, a tamed or named animal (unless the config allows them), or no mob or animal at all |
| `target_gone` | the target is gone (despawned, unloaded, or more than 64 blocks away) other than by dying |
| `unreachable` | the bot cannot walk into reach of it, or got to it three times without it being in reach |
| `wrong_dimension` | the target is in another dimension than the bot |
| `interrupted` | a reflex took over, such as fleeing at low health |
| `not_loaded`, `timeout` | a walk stopped with it, as `goto` does |

## See also

[`entities`](../world/entities.md), [`health`](../bot/health.md), [`equip`](../inventory/equip.md), [Reflexes](../../features/bots.md#reflexes).
