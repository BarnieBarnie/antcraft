# `shoot`

```text
shoot(entity)
```

Shoots arrows at a mob with the bot's bow or crossbow until it dies, keeping its distance.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `entity` | [`Entity`](../types/entity.md) | | the mob to shoot, as [`entities`](../world/entities.md) gives it |

## Returns

A [`Result`](../types/result.md), ok once the target died.

## Pauses

Yes, until the target is dead, gone or cannot be got to, or the arrows run out.

## Cost

One instruction, the call itself. Its walks plan paths as [`goto`](../movement/goto.md) does.

## Description

The bot takes a bow (or else a crossbow) it carries in hand and draws it: 20 ticks for a bow, as a player's full draw, and a crossbow's charge time (25 ticks, less with Quick Charge). Fully drawn, with the target in sight and at most 16 blocks away, it fires one of its arrows as the item fires a player's: at full power (a bow's arrow flies at 3 blocks a tick and is critical, a crossbow's at 3.15), with the weapon's enchantments, using up the arrow (none with Infinity) and wearing the weapon. Then it draws again. So a bow shoots about once a second, each arrow doing 6 to 11 damage: three kill a creeper or zombie.

The bot aims as a good player would: above the target by how far the arrow drops on its way, slowed by the air, and ahead of it by how far the target moves while the arrow flies. A moving target can still sidestep, and arrows spread a little as a player's do.

The bot keeps its distance: while the target is further than 16 blocks or out of sight, it walks closer, as `goto` walks, digging and pillaring where it must; while the target is nearer than 6 blocks, it backs off. While a creeper near it has its fuse lit, the bot backs off out of the blast as in [Fighting creepers](../../features/bots.md#fighting-creepers), drawing and shooting on meanwhile. The bot's own melee fight holds off while it shoots, so it does not run at the mob with a sword.

Any arrow shoots: plain, tipped or spectral; a crossbow's fireworks do not. Arrows the bot shot cannot be picked up again. It may attack the same targets as [`attack`](attack.md): hostile mobs and untamed, unnamed animals by default, others only where the config allows them. [`attack`](attack.md) on a creeper shoots it this way when the bot carries a bow or crossbow and arrows.

A bot at or below `botFleeHealth` with a hostile mob near it flees instead: the action ends with `interrupted` (see [Reflexes](../../features/bots.md#reflexes)).

An argument that is no `Entity` stops the script with an error.

## Examples

```python
for mob in entities("creeper", 16):
    r = shoot(mob)
    if not r:
        warn("could not shoot the creeper:", r.reason)
        break
```

## Fails with

| Reason | When |
|---|---|
| `no_tool` | the bot carries no bow or crossbow |
| `no_item` | the bot carries no arrows, or has shot them all |
| `protected` | the target may not be attacked, as for [`attack`](attack.md) |
| `target_gone` | the target is gone (despawned, unloaded, or more than 64 blocks away) other than by dying |
| `unreachable` | the bot cannot walk to where it can see the target |
| `wrong_dimension` | the target is in another dimension than the bot |
| `interrupted` | a reflex took over, such as fleeing at low health |
| `not_loaded`, `timeout` | a walk stopped with it, as `goto` does |

## See also

[`attack`](attack.md), [`entities`](../world/entities.md), [`health`](../bot/health.md), [Fighting creepers](../../features/bots.md#fighting-creepers).
