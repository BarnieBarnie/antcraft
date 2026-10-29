# `drop`

```text
drop(item, n=None)
```

Tosses carried items on the ground in front of the bot.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | [selector](../types/selectors.md) | | the items to toss |
| `n` | int | `None` | how many at most; `None` tosses every matching item |

## Returns

A [`Result`](../types/result.md); its `value` is how many items it tossed.

## Pauses

No, it is done at once.

## Cost

20 instructions besides the call.

## Description

It takes from the held stack first, then from the inventory slots in order, until `n` are tossed or no matching item is left. The items fly ahead as a player tosses them and can be picked up 2 seconds later. No bot picks up by itself what a bot dropped, but [`pick_up`](pick_up.md) takes it.

An `n` below 1, a malformed selector, or a name or tag the game does not know stops the script with an error.

## Examples

```python
r = drop("rotten_flesh")
if r:
    print("dropped", r.value)
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | the bot carries nothing `item` selects |

## See also

[`pick_up`](pick_up.md), [`put`](../crafting/put.md), [`count`](count.md).
