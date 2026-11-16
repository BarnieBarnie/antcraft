# `attack`

```text
attack(entity, min_health=None)
```

Walks to a mob and strikes it with what the bot holds until it dies.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `entity` | [`Entity`](../types/entity.md) | | the mob to fight, as [`entities`](../world/entities.md) gives it |
| `min_health` | `int` or `None` | `None` | end the fight with `hurt` once the bot's [`health()`](../bot/health.md) is under it; `None` fights on |

## Returns

A [`Result`](../types/result.md), ok once the target died.

## Pauses

Yes, until the target is dead, gone or cannot be got to, or the bot's health is under `min_health`.

## Cost

One instruction, the call itself. Its walks plan paths as [`goto`](../movement/goto.md) does.

## Description

The bot walks into melee reach of the target, as `goto` walks, digging and pillaring where it must (to a mob that flies or hovers, only to the ground under it, so it does not search for a way up into the air: one it then cannot strike is `unreachable`), and walks after it when it moves more than two blocks from where the bot was heading. In reach and in sight of it, the bot strikes with what it holds, each blow doing what a player's would with the same item (1 bare-handed, 7 with a diamond sword), as often as a player can with it at full strength: every 5 ticks bare-handed (4 blows a second), every 13 ticks with a sword (1.6 a second) and every 20 to 25 ticks with an axe (1 to 0.8 a second), from the item's attack speed; [`equip`](../inventory/equip.md) a sword first. A blow that kills the target ends the action with ok, also when something else dealt the last blow.

Every blow is fully charged, never a player's weak, hurried hit. Of the extras a player's charged blow can have, a bot's has one: with a sword it sweeps, as a player's does standing still, hurting each mob within a block of the target (and within 3 of the bot) by 1, more with Sweeping Edge, and knocking it back. The sweep spares whatever the bot may not attack (see below), such as players, other bots and named animals. A bot's blow is never a critical hit and never gives sprint knockback, as both depend on how a player moves.

By default the bot attacks only hostile mobs, such as zombies and skeletons, and animals that are neither tamed nor named. Players, other bots, tamed or named animals and anything else, such as villagers or dropped items, are `protected`. A server can allow players, other bots and tamed or named animals as targets with the config keys `botsAttackPlayers`, `botsAttackBots` and `botsAttackTamed` (see [Config](../../features/config.md)). The bot never attacks itself.

Against a creeper the bot kites: while the fuse of a creeper near it is lit, it strikes only at once and otherwise backs off out of the blast, and the attack waits until the fuse is out to walk in again (see [Fighting creepers](../../features/bots.md#fighting-creepers)). A bot that carries a bow or crossbow and arrows shoots a creeper instead, from range, as [`shoot`](shoot.md) does, so it never comes near enough to light the fuse.

A bot at or below `botFleeHealth` with a hostile mob near it flees instead of fighting on: the attack ends with `interrupted` (see [Reflexes](../../features/bots.md#reflexes)). To retreat before that, give `min_health`: once the bot's health is under it, the attack ends with `hurt` and the bot lets go of the target, so its own goals do not chase it on. A creeper it shoots (above) ends the attack with `hurt` likewise. The bundled [`jobs.guard`](../../cookbook/guard.md) fights with `min_health=12`, then walks home to eat and heal.

While a bot is hurt (under its full health) and runs a script of its own, its own goals start no fight with a hostile mob they see: the script decides, so a mob in sight does not draw out a bot that retreats or heals. A mob that hurts it is still fought back. An idle bot, or one at full health, fights any hostile mob it sees as before, but leaves neutral ones (endermen, piglins, zombified piglins) and the warden and wither alone until they hurt it (#750).

An argument that is no `Entity`, or a `min_health` that is no `int`, stops the script with an error.

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
| `hurt` | the bot's health dropped under `min_health` |
| `interrupted` | a reflex took over, such as fleeing at low health |
| `not_loaded`, `timeout` | a walk stopped with it, as `goto` does |

## See also

[`shoot`](shoot.md), [`entities`](../world/entities.md), [`health`](../bot/health.md), [`equip`](../inventory/equip.md), [Reflexes](../../features/bots.md#reflexes).
