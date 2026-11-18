# `use`

```text
use(pos, item=None, face=None)
```

Walks into reach of a block and uses the held item on it, as a player's right click does.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md), tuple or [`Entity`](../types/entity.md) | | the block to use, or the mob to use the item on (see [On a mob](#on-a-mob)) |
| `item` | [selector](../types/selectors.md) | `None` | an item to hold first, such as `"#minecraft:hoes"`; `None` uses what the bot holds |
| `face` | str | `None` | `"north"`, `"south"`, `"east"`, `"west"`, `"up"` or `"down"`: aim at the middle of that face of the block only (see [Description](#description)); not on a mob |

## Returns

A [`Result`](../types/result.md), ok when the use did something.

## Pauses

Yes: it walks, as [`goto`](../movement/goto.md) does, to where the bot sees the block within 4 blocks of its eyes, and uses it in the tick it gets there.

## Cost

One instruction, the call itself. The walk plans its path as `goto` does.

## Description

The use goes through the game's own code with a stand-in player at the bot, so items behave as for a player: a bucket fills or empties, a hoe tills and wears down, bone meal grows a crop, flint and steel lights a fire. With an empty hand it uses the block itself, such as a lever or a door.

The stand-in player stands over the middle of the block the bot's feet are in (on farmland, a slab or snow, the one over it, #660), where the walk judged what the bot sees, and looks at the first point of the block its eyes see: the middle, else the middle of a face. A line counts as seen when no other block's outline is in its way, so a cobweb or tall grass in front hides the block, as it would stop a bucket's ray; the walk judges the same way, so where it stops `use` can aim, and so do [`mine`](mine.md) and [`place`](place.md), from the same eyes. An item that casts its own ray along that look, such as a bucket, so meets this block and not one in front of it. Where every such line hits another block first, such as the floor of a 1x1 hole seen past its rim, `use` fails with `out_of_reach` and does nothing. To pour water into a hole, use the hole itself: `use(hole, "water_bucket")`.

With `face`, the look goes to just inside the middle of that face and nowhere else, and the walk goes to where the bot sees that face (#893): on a block with an outline, from the face's side, the look meeting it on that face; on air or a fluid, from the other side, the look going through the block and on out of that face. Where no such spot is in reach it fails `out_of_reach`. A bucket's ray then goes on past the face into the block beyond it. That pours water into a hole over a kelp, seagrass or other block that holds water itself: aimed at the hole, the ray would meet that block's water and the bucket would pour into it, where the water is lost; aimed at a side, `use(hole, "water_bucket", face="north")`, it meets the wall and the water goes into the hole (#877). [`jobs.kelp_farm`](../../cookbook/kelp-farm.md) refills its water cells this way.

### On a mob

Given an [`Entity`](../types/entity.md), as [`entities`](../world/entities.md) gives it, the bot walks into melee reach of the mob as [`attack`](../combat/attack.md) walks, following it as it moves, and uses the held item on it as a player's right click on a mob does (#837): wheat to a cow or sheep, carrots to a pig or seeds to a chicken puts an adult in love, so two in love near each other breed, and makes a baby grow up faster; shears shear a sheep, its wool dropped beside it for [`pick_up`](../inventory/pick_up.md); a bucket milks a cow. An animal that will not take the item now, such as a parent within its 5 minutes after breeding, a sheep already sheared or a cow fed while in love, fails with `blocked`. Any living mob may be used on but the bot itself; [`jobs.ranch`](../../cookbook/ranch.md) breeds and shears with it.

## Examples

```python
use(memory["lever"])
```

```python
water = find_nearest("water")
if water is not None:
    use(water, "bucket")
```

```python
cows = [c for c in entities("cow", 8) if not c.baby]
if len(cows) >= 2:
    use(cows[0], "wheat")
    use(cows[1], "wheat")
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | `item` is given and the bot carries nothing it selects |
| `protected` | the block may not be changed: outside the build height or border, or under spawn protection; on a mob, the bot itself |
| `blocked` | the use did nothing, such as a hoe on stone; `detail` says so, such as `using minecraft:wooden_hoe on minecraft:stone at Pos(x, y, z) did nothing` (or `using an empty hand on ...`); on a mob, such as shears on a sheared sheep |
| `target_gone`, `unreachable` | on a mob: it is gone, or the bot cannot walk into reach of it, as for `attack` |
| `out_of_reach` | the bot could not walk into reach, or from where it stands every line from its eyes to the block hits another block first |
| `not_loaded` | the block is in a chunk that is not loaded |
| `wrong_dimension` | `pos` or the mob is in another dimension than the bot's |
| `timeout` | the walk got stuck |

## See also

[`place`](place.md), [`mine`](mine.md), [`equip`](../inventory/equip.md).
