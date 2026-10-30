# `lib/craft`

Crafting an item through its whole recipe tree. A module of the mod's default library: `import lib.craft`, then `lib.craft.craft_all("stone_pickaxe")` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/craft.py`, written with [`needs`](../../crafting/needs.md), [`craft`](../../crafting/craft.md) and [`smelt`](../../crafting/smelt.md).

## `craft_all(item, n=1, table=None, furnace=None)`

Makes `n` of `item` from what the bot carries: it asks `needs(item, n)` for the steps and runs each in order, `craft` at `table` or `smelt` in `furnace` (`None`: one within reach, as those actions take it). A smelting step of more than a stack takes several `smelt` calls. It gathers nothing: fetch the raw items first, with [`take`](../../crafting/take.md) from a chest or by mining.

- **Returns** a dict as [`dig_shaft`](shaft.md#dig_shaftlevels) gives: `ok`, `reason`, `at` (always `None`) and `missing`.
- **Pauses** yes, while it walks to the table or furnace and waits for the smelting.

| Reason | When |
|---|---|
| `missing_ingredients` | before it crafts anything, the bot lacks raw items; `missing` is `needs`'s `gather`, what to fetch |
| another reason | the first `craft` or `smelt` step that failed, such as `no_table` (see [`Result`](../../types/result.md)); the steps before it are made |

An item the game does not know, or an `n` outside 1 to 1000000, stops the script with an error, as for `needs`.

## Example

Take what a stone pickaxe takes from a chest, then make it:

```python
import lib.craft

chest = memory["chest"]
take(chest, "cobblestone", 3)
take(chest, "#minecraft:logs", 1)
r = lib.craft.craft_all("stone_pickaxe", table=memory["table"])
if not r["ok"]:
    warn(f"no pickaxe: {r['reason']} {r['missing']}")
```

## See also

[`needs`](../../crafting/needs.md), [`craft`](../../crafting/craft.md), [`smelt`](../../crafting/smelt.md), [Containers and crafting](../../index.md#containers-and-crafting).
