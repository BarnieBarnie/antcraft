# `foods`

```text
foods()
```

The foods the game knows, modded ones included, with the food points each gives.

## Parameters

None.

## Returns

A list of `(item, points)` tuples, such as `("minecraft:bread", 5)`, in the item registry's order: every item with a food component and no effect on eating. So it leaves out what poisons or hurts (raw chicken, rotten flesh, spider eyes, pufferfish, poisonous potatoes), chorus fruit, which teleports, golden apples and honey. Suspicious stew is in it: its effects are a component of its own.

## Pauses

No, it is done at once.

## Cost

20 instructions besides the call. The list is read from the game once, the first time a script asks, and copied after.

## Description

[`lib.food`](../modules/lib/food.md) builds `FOODS`, what a bot eats, from it (#722), so a bot eats a modded food without a script naming it. It does not look at the bot or the world.

## Examples

The food points of what the bot carries:

```python
points = dict(foods())
print([(s.item, points[s.item]) for s in inventory() if s.item in points])
```

## See also

[`eat`](eat.md), [`food`](../bot/food.md), [`lib/food`](../modules/lib/food.md).
