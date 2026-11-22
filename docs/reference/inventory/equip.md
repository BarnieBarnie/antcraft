# `equip`

```text
equip(item)
```

Holds a carried item in the bot's hand.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | [selector](../types/selectors.md) or [`Stack`](../types/stack.md) | | the item to hold, such as `"stone_pickaxe"` or `"#minecraft:pickaxes"`, or one stack from [`inventory`](inventory.md) |

## Returns

A [`Result`](../types/result.md), ok when the bot holds a matching item.

## Pauses

No, it is done at once.

## Cost

15 instructions besides the call.

## Description

If the held item already matches, nothing changes. Otherwise the first matching stack in the inventory goes to the hand, and what the bot held goes to that stack's slot. Given a [`Stack`](../types/stack.md), the bot holds a carried stack of the same item, damage and enchantments (the count may differ), so a script picks one of two rods: [`fish`](../blocks/fish.md) keeps the rod in hand, so it fishes with the one equipped (#993). A malformed selector, or a name or tag the game does not know, stops the script with an error.

Most actions pick what to hold themselves: `mine` the best tool carried, `place` the block, `use` its `item`. `equip` is for what the bot should hold besides, such as a sword before a fight. An animal follows a bot that holds its food, as it follows a player, but up to 1.5 blocks close: wheat for a cow or sheep, carrots for a pig, seeds for a chicken; [`jobs.ranch`](../../cookbook/ranch.md) lures animals into its pen so (#843).

## Examples

```python
if not equip("#minecraft:swords"):
    say("no sword")

best = max([s for s in inventory() if s.item == "minecraft:fishing_rod"],
           key=lambda s: len(s.enchantments))
equip(best)
fish()
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | the bot carries nothing `item` selects, or no stack equal to the `Stack` |

## See also

[`held`](held.md), [`inventory`](inventory.md), [`has`](has.md).
