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
| `out_of_reach` | the bot could not walk into reach |
| `not_loaded` | the block is in a chunk that is not loaded |
| `wrong_dimension` | `pos` is in another dimension than the bot's |
| `timeout` | the walk got stuck |

## See also

[`place`](place.md), [`mine`](mine.md), [`equip`](../inventory/equip.md).
