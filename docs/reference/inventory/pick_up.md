# `pick_up`

```text
pick_up(what=None, radius=8)
```

Walks to dropped items around the bot and takes them.

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

One instruction, the call itself. Its walks plan paths as [`goto`](../index.md#movement) does.

## Description

It goes to the nearest matching item first, then to the nearest from where it then stands. By an item still in the air, or one just dropped, it waits until it may be taken. An item it could not walk to, or got to twice without taking, it skips. Items that do not fit in its inventory stay where they lie. What it took is how many more matching items the bot carries than when it started, so items it walks over on the way count too.

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

[`items_near`](../index.md#the-world-around), [`drop`](drop.md), [`free_slots`](free_slots.md).
