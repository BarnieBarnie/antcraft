# `craft`

```text
craft(item, n=1, table=None)
```

Crafts an item from carried items, at a crafting table if the recipe needs one.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | str | | the one item to make, such as `"oak_planks"`; a tag or a list is an error |
| `n` | int | `1` | how many to make at least |
| `table` | [`Pos`](../types/pos.md) or tuple | `None` | a crafting table to walk to; `None` uses one within reach, if the recipe needs a table |

## Returns

A [`Result`](../types/result.md); its `value` is how many items it made. That can be more than `n`, as a recipe makes several at a time (four planks from a log).

## Pauses

A 2x2 recipe is crafted at once, without pausing. A 3x3 recipe pauses: at a `table` the bot does not see within 4 blocks of its eyes it walks there first, as [`goto`](../index.md#movement) does, then crafts in the tick it gets there.

## Cost

40 instructions besides the call. A walk to `table` plans its path as `goto` does.

## Description

It takes the first crafting recipe for `item` whose ingredients the bot carries, its hand included, and works it again and again until it made at least `n` or runs out. It works that one recipe only, so a bot carrying birch logs and oak logs makes planks from one kind.

2x2 recipes are crafted anywhere, as in a player's inventory. 3x3 recipes need a crafting table at `table`, or without `table` one the bot sees within 4 blocks of its eyes where it stands; it does not look further. An `n` below 1, or an item the game does not know, stops the script with an error.

## Examples

```python
craft("oak_planks", 8)
craft("stick", 4)
table = find_nearest("crafting_table", radius=16)
r = craft("wooden_pickaxe", table=table)
if not r:
    say("could not craft a pickaxe: " + r.reason)
```

## Fails with

| Reason | When |
|---|---|
| `no_recipe` | no crafting recipe makes `item` |
| `missing_ingredients` | the bot does not carry what any recipe needs, or ran out before `n` were made; `value` is how many it made |
| `no_table` | a 3x3 recipe, and no crafting table at `table` or within reach |
| `no_space` | what it made does not fit; `value` is how many it made |
| `protected`, `out_of_reach`, `not_loaded`, `timeout` | at a `table`, as for [`use`](../blocks/use.md) |
| `wrong_dimension` | `table` is in another dimension than the bot's |

## See also

[`smelt`](smelt.md), [`has`](../inventory/has.md), [`find_nearest`](../index.md#the-world-around).
