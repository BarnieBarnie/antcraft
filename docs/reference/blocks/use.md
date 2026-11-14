# `use`

```text
use(pos, item=None)
```

Walks into reach of a block and uses the held item on it, as a player's right click does.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | the block to use |
| `item` | [selector](../types/selectors.md) | `None` | an item to hold first, such as `"#minecraft:hoes"`; `None` uses what the bot holds |

## Returns

A [`Result`](../types/result.md), ok when the use did something.

## Pauses

Yes: it walks, as [`goto`](../movement/goto.md) does, to where the bot sees the block within 4 blocks of its eyes, and uses it in the tick it gets there.

## Cost

One instruction, the call itself. The walk plans its path as `goto` does.

## Description

The use goes through the game's own code with a stand-in player at the bot, so items behave as for a player: a bucket fills or empties, a hoe tills and wears down, bone meal grows a crop, flint and steel lights a fire. With an empty hand it uses the block itself, such as a lever or a door.

The stand-in player stands over the middle of the bot's block, where the walk judged what the bot sees, and looks at the first point of the block its eyes see: the middle, else the middle of a face. A line counts as seen when no other block's outline is in its way, so a cobweb or tall grass in front hides the block, as it would stop a bucket's ray; the walk judges the same way, so where it stops `use` can aim, and so do [`mine`](mine.md) and [`place`](place.md), from the same eyes. An item that casts its own ray along that look, such as a bucket, so meets this block and not one in front of it. Where every such line hits another block first, such as the floor of a 1x1 hole seen past its rim, `use` fails with `out_of_reach` and does nothing. To pour water into a hole, use the hole itself: `use(hole, "water_bucket")`.

## Examples

```python
use(memory["lever"])
```

```python
water = find_nearest("water")
if water is not None:
    use(water, "bucket")
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | `item` is given and the bot carries nothing it selects |
| `protected` | the block may not be changed: outside the build height or border, or under spawn protection |
| `blocked` | the use did nothing, such as a hoe on stone |
| `out_of_reach` | the bot could not walk into reach, or from where it stands every line from its eyes to the block hits another block first |
| `not_loaded` | the block is in a chunk that is not loaded |
| `wrong_dimension` | `pos` is in another dimension than the bot's |
| `timeout` | the walk got stuck |

## See also

[`place`](place.md), [`mine`](mine.md), [`equip`](../inventory/equip.md).
