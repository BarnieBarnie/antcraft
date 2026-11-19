# `needs`

```text
needs(item, n=1, also=None, extra=None)
```

What the bot still has to gather, and what to craft and smelt from it, to make `n` of `item` from what it carries, and one of each item of `also` before it.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | str | | the one item to make, such as `"stone_pickaxe"`; a tag or a list is an error |
| `n` | int | `1` | how many to make, from 1 to 1000000 |
| `also` | list of str | `None` | items to make one each of first, from the same carried items, such as `["crafting_table"]`; each one item as `item` |
| `extra` | dict of str to int | `None` | items to count as carried besides what the bot carries, such as those a chest holds: item id to count, each one item as `item` and a count of at least 0 |

## Returns

A dict with seven keys:

- `"gather"`: a dict of raw item id to count, what the bot must gather before it can make `item`, given what it carries, its hand included. `{}` when it carries all it needs.
- `"crafts"`: a list of steps in order, ingredients before what they make, each a tuple `(item, n, how)`: the item id, how many the step makes (a whole number of recipe outputs, so four planks from one log) and `"craft"` or `"smelt"`, so a script can run each one with [`craft`](craft.md) or [`smelt`](smelt.md). `[]` when the bot carries `n` of `item` already.
- `"uses"`: for each step of `"crafts"`, in the same order, a dict of the item ids it takes to how many: where an ingredient may be any of several items, those the plan picked. Pass them to `craft`'s `use` and `smelt` them, so a step does not take what the plan keeps for a later one: oak signs from six oak and two birch planks make the sticks of the birch planks, where `craft` alone would take the oak planks it has most of (#935).
- `"table"`: `True` where a `"craft"` step needs the 3x3 grid of a crafting table, `False` where every recipe fits the bot's own 2x2 grid.
- `"inputs"`: for each `"smelt"` step, what it smelts, as `smelt` takes it: the step's item id to the list of item ids any of which smelts into it. `smelt` takes the input, not what it makes.
- `"fuel"`: for each `"smelt"` step, what the plan burns for it, as `smelt`'s `fuel` takes it: the step's item id to the list of fuel item ids. Pass it on, so `smelt` does not burn coal the plan keeps for a later step.
- `"burns"`: for each step of `"crafts"`, in the same order, a dict of the fuel item ids it burns to how many, in the order it burns them; `{}` for a `"craft"` step. Nine iron ingots from one coal and five oak planks burn `{"minecraft:coal": 1, "minecraft:oak_planks": 1}`. [`smelt_here`](../modules/lib/craft.md#smelt_hereitem-n1-fuelnone) takes it as `fuel`, and keeps that many of each back from the furnace and table it crafts (#945).

```text
needs("stone_pickaxe")
{"gather": {"minecraft:cobblestone": 3, "minecraft:oak_log": 1},
 "crafts": [("minecraft:oak_planks", 4, "craft"), ("minecraft:stick", 4, "craft"), ("minecraft:stone_pickaxe", 1, "craft")],
 "uses": [{"minecraft:oak_log": 1}, {"minecraft:oak_planks": 2}, {"minecraft:cobblestone": 3, "minecraft:stick": 2}],
 "inputs": {}, "fuel": {}, "table": True}
```

With `also`, the steps of those items come first and use up carried items before `item` does, so `gather` is what all of them need together. A bot with four planks and two sticks needs nothing more for a wooden pickaxe, but a log for the pickaxe and a crafting table:

```text
needs("wooden_pickaxe", also=["crafting_table"])
{"gather": {"minecraft:oak_log": 1},
 "crafts": [("minecraft:crafting_table", 1, "craft"), ("minecraft:oak_planks", 4, "craft"), ("minecraft:wooden_pickaxe", 1, "craft")],
 "uses": [{"minecraft:oak_planks": 4}, {"minecraft:oak_log": 1}, {"minecraft:oak_planks": 3, "minecraft:stick": 2}],
 "inputs": {}, "fuel": {}, "table": True}
```

With `extra`, those items are used as if carried, so a plan can count what a chest holds without taking it first. With four sticks in a chest and one oak log carried, a wooden pickaxe needs only the log's planks:

```text
needs("wooden_pickaxe", extra={"stick": 4})
{"gather": {},
 "crafts": [("minecraft:oak_planks", 4, "craft"), ("minecraft:wooden_pickaxe", 1, "craft")],
 "uses": [{"minecraft:oak_log": 1}, {"minecraft:oak_planks": 3, "minecraft:stick": 2}],
 "inputs": {}, "fuel": {}, "table": True}
```

[`lib/craft`](../modules/lib/craft.md)'s `craft_all` runs these steps for a script, and its `plan` adds the crafting table and furnace the bot must craft with `also`.

## Pauses

No, it is done at once. It only plans: it crafts and gathers nothing.

## Cost

3000 instructions besides the call, about the time it takes to plan (some 60 µs).

## Description

It works through the game's recipes, data packs included, down to raw items: those of the item tag `antcraft:gatherable` (logs, cobblestone, raw iron, coal, sand and the like), and anything no recipe makes. What the bot carries is used first at every level, so with two sticks carried a stone pickaxe needs only the cobblestone, and the steps are only the pickaxe. Where a recipe takes any of several items (any planks for sticks), it picks the one that needs the least gathering, then the fewest crafts, and the first of them on a tie (oak).

A smelting step also needs fuel: it counts on burning what the bot carries (coal, then charcoal, then logs and planks), and what is short is in `gather`, as coal, unless making fuel from carried logs uses up less, which adds that step: planks, four from a log that each burn as long as it, so one item smelted from a carried log burns a plank and keeps three, or charcoal. `fuel` says which it burns: eight torches from one coal and one log put the coal in the torches and burn a plank to smelt the log into charcoal, where `smelt` without `fuel` would burn the coal. `smelt` loads at most a stack a call, so a `"smelt"` step of more than 64 takes several calls.

An item no recipe makes and that is not in the tag, such as an elytra, is in `gather` itself. An `n` outside 1 to 1000000, an item the game does not know, in `item`, `also` or `extra`, or an `extra` that is not a dict or has a count under 0, stops the script with an error.

## Examples

```python
r = needs("stone_pickaxe")
if r["gather"]:
    say("still need " + str(r["gather"]))
else:
    table = find_nearest("crafting_table", radius=16)
    for (item, n, how), uses in zip(r["crafts"], r["uses"]):
        if how == "craft":
            craft(item, n, table=table, use=list(uses))
        else:
            for what, m in uses.items():
                smelt(what, m, fuel=r["fuel"][item])
```

## See also

[`craft`](craft.md), [`smelt`](smelt.md), [`count`](../inventory/count.md), [`has`](../inventory/has.md).
