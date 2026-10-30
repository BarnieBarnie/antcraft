# `needs`

```text
needs(item, n=1)
```

What the bot still has to gather, and what to craft and smelt from it, to make `n` of `item` from what it carries.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | str | | the one item to make, such as `"stone_pickaxe"`; a tag or a list is an error |
| `n` | int | `1` | how many to make, from 1 to 1000000 |

## Returns

A dict with three keys:

- `"gather"`: a dict of raw item id to count, what the bot must gather before it can make `item`, given what it carries, its hand included. `{}` when it carries all it needs.
- `"crafts"`: a list of steps in order, ingredients before what they make, each a tuple `(item, n, how)`: the item id, how many the step makes (a whole number of recipe outputs, so four planks from one log) and `"craft"` or `"smelt"`, so a script can run each one with [`craft`](craft.md) or [`smelt`](smelt.md). `[]` when the bot carries `n` of `item` already.
- `"inputs"`: for each `"smelt"` step, what it smelts, as `smelt` takes it: the step's item id to the list of item ids any of which smelts into it. `smelt` takes the input, not what it makes.

```text
needs("stone_pickaxe")
{"gather": {"minecraft:cobblestone": 3, "minecraft:oak_log": 1},
 "crafts": [("minecraft:oak_planks", 4, "craft"), ("minecraft:stick", 4, "craft"), ("minecraft:stone_pickaxe", 1, "craft")],
 "inputs": {}}
```

[`lib/craft`](../modules/lib/craft.md)'s `craft_all` runs these steps for a script.

## Pauses

No, it is done at once. It only plans: it crafts and gathers nothing.

## Cost

3000 instructions besides the call, about the time it takes to plan (some 60 µs).

## Description

It works through the game's recipes, data packs included, down to raw items: those of the item tag `antcraft:gatherable` (logs, cobblestone, raw iron, coal, sand and the like), and anything no recipe makes. What the bot carries is used first at every level, so with two sticks carried a stone pickaxe needs only the cobblestone, and the steps are only the pickaxe. Where a recipe takes any of several items (any planks for sticks), it picks the one that needs the least gathering, then the fewest crafts, and the first of them on a tie (oak).

A smelting step also needs fuel: it counts on burning what the bot carries (coal, then charcoal, then logs), and what is short is in `gather`, as coal, unless making charcoal from carried logs uses up less, which adds that smelting step. `smelt` loads at most a stack a call, so a `"smelt"` step of more than 64 takes several calls.

An item no recipe makes and that is not in the tag, such as an elytra, is in `gather` itself. An `n` outside 1 to 1000000, or an item the game does not know, stops the script with an error.

## Examples

```python
r = needs("stone_pickaxe")
if r["gather"]:
    say("still need " + str(r["gather"]))
else:
    table = find_nearest("crafting_table", radius=16)
    for item, n, how in r["crafts"]:
        if how == "craft":
            craft(item, n, table=table)
        else:
            smelt(r["inputs"][item], n)
```

## See also

[`craft`](craft.md), [`smelt`](smelt.md), [`count`](../inventory/count.md), [`has`](../inventory/has.md).
