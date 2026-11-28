# `pick_up`

```text
pick_up(what=None, radius=8)
```

Walks to dropped items around the bot, and arrows stuck in blocks that a player could pick up, and takes them.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `what` | [selector](../types/selectors.md) | `None` | the items to take; `None` takes any |
| `radius` | int | `8` | how far from where the bot stood when called, at most 16 |

## Returns

A [`Result`](../types/result.md); its `value` is how many items it took, also when it fails.

## Pauses

Yes, until no matching item that the bot can walk to and has room for is left within the radius.

## Cost

One instruction, the call itself. Its walks plan paths as [`goto`](../movement/goto.md) does.

## Description

It goes to the nearest matching item first, then to the nearest from where it then stands. Finding none, it looks on for two more ticks, and as long as the world within the radius is still settling, for two seconds at most: a block falling, such as sand, or a block or fluid about to change, such as water flowing; not fire or a redstone component, which tick on for ever and drop nothing (#981). What a [`mine`](../blocks/mine.md) just before leaves may drop later: the cane over cut cane a tick after, sand over a mined block that falls onto a torch and breaks into an item, or a torch that water held back by the mined block washes off some ten ticks after (#965, #971, #978). An item still falling it lets land before it walks to it, wherever the bot stands: a walk to where the item was in the air pillared there, as over cane just cut, its scaffold put in place of the cane under it (#853); one not landed after two seconds, such as an item with no gravity, it skips (#913). One afloat in water or lava it walks beside, within two blocks, and reaches in from there, down two blocks below its feet, so also from scaffold over the liquid (#904): a walk to within a block of one in a farm's water dug the soil beside it (#1029). Only where it finds no way beside it, or the item is out of reach from there, as far out in a lake, it walks to the item itself. By an item still in the air, or one just dropped, it waits until it may be taken, five seconds at most: one it may never take, it skips (#920). An item lying in a block it cannot walk onto, such as bone meal in a composter, it takes from beside the block and never breaks the block for it (#817); so too one in a block a path never enters, such as berries in a sweet berry bush, which hurts (#832). One that drifts two blocks or more from where the bot got to it, as in a current, it goes after again (#1031). An item it could not walk to, or got to twice without taking, it skips. Items that do not fit in its inventory stay where they lie. An arrow stuck in a block counts as a dropped arrow: one the bot or a player shot from a bow or crossbow without Infinity, such as those [`shoot`](../combat/shoot.md) misses with (#720); not a skeleton's. What it took is how many more matching items the bot carries than when it started, so items it walks over on the way count too.

A `radius` below 0 or above 16, a malformed selector, or a name or tag the game does not know stops the script with an error.

## Examples

```python
mine(memory["tree"])
pick_up("#minecraft:saplings", radius=6)
```

## Fails with

| Reason | When |
|---|---|
| `target_gone` | no such item is around, or it went before the bot took one |
| `no_space` | items it could get to are left because they do not fit; `value` is how many it took |
| `out_of_reach` | it took none, and some could not be walked to |
| `not_loaded`, `timeout` | a walk stopped with it, as `goto` does |

## See also

[`items_near`](../world/items_near.md), [`drop`](drop.md), [`free_slots`](free_slots.md).
