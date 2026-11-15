# `eat`

```text
eat(item)
```

Action: eats or drinks a carried item.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | [selector](../types/selectors.md) | | what to eat, such as `"bread"` or `"#minecraft:meat"` |

## Returns

A [`Result`](../types/result.md), ok once the bot has eaten.

## Pauses

Yes, for the item's eat time, as a player's: 32 ticks (1.6 seconds) for most food, 16 for dried kelp.

## Cost

One instruction, the call itself.

## Description

Eats the first carried item `item` selects that can be eaten or drunk, held or in the inventory. Once its eat time is over the bot gets the item's food and saturation ([`food()`](../bot/food.md), [`saturation()`](../bot/saturation.md)) and its effects, such as a golden apple's Regeneration and Absorption or rotten flesh's Hunger, and one of the item is used up; what is left of it, such as a stew's bowl, goes to the inventory. A bot at 20 food eats only what can always be eaten, such as a golden apple, as a player. See [Hunger](../../features/bots.md#hunger).

## Examples

```python
while food() < 20 and has("#minecraft:meat"):
    eat("#minecraft:meat")
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | the bot carries nothing `item` selects that can be eaten or drunk, or it is gone before the bot is done |
| `full` | the bot is at 20 food and the item is not one that can always be eaten |
| `interrupted` | a reflex took over while the bot ate |

## See also

[`food`](../bot/food.md), [`saturation`](../bot/saturation.md), [`has`](has.md).
