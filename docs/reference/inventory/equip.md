# `equip`

```text
equip(item)
```

Holds a carried item in the bot's hand.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | [selector](../types/selectors.md) | | the item to hold, such as `"stone_pickaxe"` or `"#minecraft:pickaxes"` |

## Returns

A [`Result`](../types/result.md), ok when the bot holds a matching item.

## Pauses

No, it is done at once.

## Cost

15 instructions besides the call.

## Description

If the held item already matches, nothing changes. Otherwise the first matching stack in the inventory goes to the hand, and what the bot held goes to that stack's slot. A malformed selector, or a name or tag the game does not know, stops the script with an error.

Most actions pick what to hold themselves: `mine` the best tool carried, `place` the block, `use` its `item`. `equip` is for what the bot should hold besides, such as a sword before a fight.

## Examples

```python
if not equip("#minecraft:swords"):
    say("no sword")
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | the bot carries nothing `item` selects |

## See also

[`held`](held.md), [`inventory`](inventory.md), [`has`](has.md).
