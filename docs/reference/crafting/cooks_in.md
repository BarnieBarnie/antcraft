# `cooks_in`

```text
cooks_in(item)
```

Which of the furnace, smoker and blast furnace have a recipe for `item`, by the game's recipes, data packs included.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | str | | the one item to cook, such as `"kelp"`; a tag or a list is an error |

## Returns

A list of block ids, of `"minecraft:furnace"`, `"minecraft:smoker"` and `"minecraft:blast_furnace"` in that order, the stations that cook `item`; `[]` where none does. `cooks_in("kelp")` is `["minecraft:furnace", "minecraft:smoker"]`, `cooks_in("chorus_fruit")` is `["minecraft:furnace"]`.

## Pauses

No, it is done at once.

## Cost

15 instructions besides the call.

## Description

It asks the recipes, not item tags, so it is right for kelp, which is in no food tag, and for a modded food or ore. An item the game does not know stops the script with an error.

## Examples

```python
smoker = find_nearest("smoker", radius=16)
if smoker is not None and "minecraft:smoker" in cooks_in("beef"):
    smelt("beef", 1, furnace=smoker)
```

## See also

[`smelt`](smelt.md), [`needs`](needs.md), [`lib/craft`](../modules/lib/craft.md)'s `plan`.
