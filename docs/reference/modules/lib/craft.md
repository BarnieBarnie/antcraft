# `lib/craft`

Crafting and smelting an item through its whole recipe tree, with a crafting table or furnace put down and taken back if need be. A module of the mod's default library: `import lib.craft`, then `lib.craft.craft_all("stone_pickaxe")` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/craft.py`, written with [`needs`](../../crafting/needs.md), [`craft`](../../crafting/craft.md) and [`smelt`](../../crafting/smelt.md); `obtain` also takes the raw items from chests with [`lib/inv`](inv.md)'s `take_all`.

## `craft_all(item, n=1, table=None, furnace=None)`

Makes `n` of `item` from what the bot carries: it asks `needs(item, n)` for the steps and runs each in order, `craft` at `table` or `smelt` in `furnace` (`None`: one within reach, as those actions take it; for a smelting step without `furnace`, `smelt_here` (below), which puts one down where none is in reach). A smelting step of more than a stack takes several `smelt` calls. It gathers nothing: fetch the raw items first, with [`take`](../../crafting/take.md) from a chest or by mining.

- **Returns** a dict as [`dig_shaft`](shaft.md#dig_shaftlevels) gives: `ok`, `reason`, `at` (always `None`) and `missing`.
- **Pauses** yes, while it walks to the table or furnace and waits for the smelting.

| Reason | When |
|---|---|
| `missing_ingredients` | before it crafts anything, the bot lacks raw items; `missing` is `needs`'s `gather`, what to fetch |
| another reason | the first `craft` or `smelt` step that failed, such as `no_table` (see [`Result`](../../types/result.md)); the steps before it are made |

An item the game does not know, or an `n` outside 1 to 1000000, stops the script with an error, as for `needs`.

## `craft_here(item, n=1)`

`craft_all(item, n)` for a bot away from any crafting table. Where a step finds no table within reach (`no_table`), it puts one down beside the bot, as a player does: the table it carries, else one it first crafts from carried planks or logs. It places it on the first free spot north, south, east or west of the bot's feet, runs `craft_all(item, n, table=spot)`, then mines the table and picks it up again, also when a step fails. With a table within reach, or only 2x2 recipes, it is `craft_all` as is and places nothing.

The steps made before the `no_table` stay made, and a table it crafts takes its planks from what the bot carries first: with planks for only one of the two, the table is made and the item then lacks them (`missing_ingredients`).

- **Returns** what `craft_all` gives for the item, or `no_table` (the steps before it made) where the bot could not craft a table or no spot beside it took one.
- **Pauses** yes, as `craft_all` does, and while it mines the table back.

## `smelt_here(item, n=1, fuel=None)`

[`smelt`](../../crafting/smelt.md)`(item, n, fuel=fuel)` for a bot away from any furnace: `item` is the input, such as `"raw_iron"`. Where no furnace is within reach (`no_furnace`), it puts one down beside the bot as `craft_here` does a table: the furnace it carries, else one it first crafts from 8 carried cobblestone with `craft_here`. It smelts in it, then mines the furnace and picks it up again, with anything a failed smelt left in it, also when the smelt fails. With a furnace within reach it is `smelt` as is.

Mining the furnace back takes a pickaxe: without one it stays where it was put down.

- **Returns** `smelt`'s [`Result`](../../types/result.md), or its `no_furnace` where the bot could not craft a furnace or no spot beside it took one.
- **Pauses** yes, as `smelt` does, and while it crafts, places and mines the furnace.

## `obtain(item, n=1, chests=None)`

Makes the bot carry `n` of `item`, raw items fetched from storage: where it carries `n` already it is done. Otherwise it asks [`needs`](../../crafting/needs.md)`(item, n)` what to gather and takes that from each chest of `chests` in turn (a list of [`Pos`](../../types/pos.md); `None`: the [`colony`](../../colony/colony.md)'s Colony Core, no chest for a bot in no colony), with [`lib/inv`](inv.md)'s `take_all`, so raw items spread over several chests are all used. Then it runs `craft_here(item, n)`, which puts a crafting table and furnace down as need be.

Where `craft_here` gives `no_table` or `no_furnace` and the bot carries none, it obtains the table or furnace the same way, from the same chests, and runs `craft_here` once more: a table crafted from the planks the item's sticks were made from takes another log from the chest. That table or furnace stays carried.

- **Returns** a dict as `craft_all` gives: `ok`, or `missing_ingredients` with `missing`, item id to how many the chests lack, before it crafts anything (what it could take stays taken), or what `craft_here` gives.
- **Pauses** yes, while it walks to each chest and as `craft_here` does.

The two halves `craft_here` and `smelt_here` use are in the module too: `place_beside(block)` places a carried block on such a spot and gives its [`Pos`](../../types/pos.md), `None` where no spot took it; `take_back(spot, block)` [`mine`](../../blocks/mine.md)s it and [`pick_up`](../../inventory/pick_up.md)s it where it did not fit.

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

Away from base, with logs and cobblestone carried and no table:

```python
import lib.craft

r = lib.craft.craft_here("stone_pickaxe")
```

With raw iron, sticks, coal, 8 cobblestone and a pickaxe carried, `craft_here` also puts down a furnace for the ingots:

```python
import lib.craft

r = lib.craft.craft_here("iron_pickaxe")
```

An empty-handed bot beside a chest of logs and cobblestone:

```python
import lib.craft

r = lib.craft.obtain("stone_pickaxe", chests=[memory["chest"]])
if not r["ok"]:
    warn(f"no pickaxe: {r['reason']} {r['missing']}")
```

## See also

[`needs`](../../crafting/needs.md), [`craft`](../../crafting/craft.md), [`smelt`](../../crafting/smelt.md), [`place`](../../blocks/place.md), [Containers and crafting](../../index.md#containers-and-crafting).
