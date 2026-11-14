# `lib/craft`

Crafting and smelting an item through its whole recipe tree, with a crafting table or furnace put down and taken back if need be. A module of the mod's default library: `import lib.craft`, then `lib.craft.craft_all("stone_pickaxe")` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/craft.py`, written with [`needs`](../../crafting/needs.md), [`craft`](../../crafting/craft.md) and [`smelt`](../../crafting/smelt.md). It gathers nothing: [`lib/get`](get.md)'s `get` gets the raw items from chests and the world and crafts with this module.

## `craft_all(item, n=1, table=None, furnace=None)`

Makes `n` of `item` from what the bot carries: it asks `needs(item, n)` for the steps and runs each in order, `craft` at `table` or `smelt` in `furnace` (`None`: one within reach, as those actions take it; for a smelting step without `furnace`, `smelt_here` (below), which puts one down where none is in reach), burning the fuel `needs` gives for the step. A smelting step of more than a stack takes several `smelt` calls. It gathers nothing: fetch the raw items first, with [`take`](../../crafting/take.md) from a chest or by mining.

- **Returns** a dict as [`dig_shaft`](shaft.md#dig_shaftlevels) gives: `ok`, `reason`, `at` (always `None`) and `missing`.
- **Pauses** yes, while it walks to the table or furnace and waits for the smelting.

| Reason | When |
|---|---|
| `missing_ingredients` | before it crafts anything, the bot lacks raw items; `missing` is `needs`'s `gather`, what to fetch |
| another reason | the first `craft` or `smelt` step that failed, such as `no_table` (see [`Result`](../../types/result.md)); the steps before it are made |

An item the game does not know, or an `n` outside 1 to 1000000, stops the script with an error, as for `needs`.

## `craft_here(item, n=1)`

`craft_all(item, n)` for a bot away from any crafting table. Where a step finds no table within reach (`no_table`), it uses a table that [`find_nearest`](../../world/find_nearest.md) finds within `REACH` (4) blocks, which `craft` walks to (the bot can stand within 4 blocks of a table yet not see it from its eyes); else it puts one down beside the bot, as a player does: the table it carries, else one it first crafts from carried planks or logs. It places it on the first free spot north, south, east or west of the bot's feet, else of its head, else above its head; in a pit where none is free, it [`mine`](../../blocks/mine.md)s out a spot beside its feet, keeping what it mined, and places it there. Then it runs `craft_all(item, n, table=spot)`, then mines the table and picks it up again, also when a step fails. With a table within reach, or only 2x2 recipes, it is `craft_all` as is and places nothing.

Before it crafts anything it asks `plan(item, n)` (below) what to gather, the table and furnace it must craft included: with planks for the item or a table but not both, it crafts nothing and gives `missing_ingredients` with the logs both lack. Where `plan`'s `no_tool` is `True` (a step smelts, no station within `REACH` cooks it and the bot carries no pickaxe), it crafts nothing and gives `no_tool`, as `smelt_here` would for the furnace it could not mine back.

- **Returns** what `craft_all` gives for the item, `missing_ingredients` with `plan`'s `gather` or `no_tool` before it crafts anything, or `no_table` (the steps before it made) where no spot beside the bot took a table.
- **Pauses** yes, as `craft_all` does, and while it mines the table back.

## `smelt_here(item, n=1, fuel=None)`

[`smelt`](../../crafting/smelt.md)`(item, n, fuel=fuel)` for a bot away from any furnace: `item` is the input, such as `"raw_iron"`. Where no furnace is within reach (`no_furnace`), it puts one down beside the bot as `craft_here` does a table, on the same spots, mining one out in a pit: the furnace it carries, else one it first crafts from 8 carried cobblestone with `craft_here`. It plans that furnace with the `n` of `item` it smelts kept back, so `smelt_here("cobblestone", 8)` with only 8 cobblestone crafts no furnace from them. It keeps back the fuel too, `fuel` or else the fuel `smelt` would burn, one for each 1.5 items smelted (as wood burns, the only fuel a table or furnace takes), so 4 planks to burn crafts no table from them. Since `smelt` loads the input before it burns anything, the input counts as fuel only where more of it is carried: `smelt_here("oak_log")` with one log burns planks, not that log: where the stations, the input and the fuel together lack raw items it crafts nothing and gives `missing_ingredients` with `missing`, what they lack. It smelts in it, then mines the furnace and picks it up again, with anything a failed smelt left in it, also when the smelt fails. With a furnace, or a blast furnace or smoker that cooks `item`, within reach it is `smelt` as is.

Mining the furnace back takes a pickaxe: where it would put one down and the bot carries no `#minecraft:pickaxes`, it places and crafts none and gives `no_tool`, with the inventory as it was.

- **Returns** a dict as `craft_all` gives: `ok`, or `smelt`'s reason (see [`Result`](../../types/result.md)), `no_tool` where the bot carries no pickaxe to mine the furnace back, `missing_ingredients` before it crafts anything, or `no_furnace` where the bot could not craft a furnace or no spot beside it took one.
- **Pauses** yes, as `smelt` does, and while it crafts, places and mines the furnace.

## Kept for old scripts

`obtain`, `ensure_tool` and `fetch` were this module's chest-only ways to get an item or tool. They are kept so older scripts still run, each now a wrapper of the library function that does it; new scripts call that one.

| Function | Now |
|---|---|
| `obtain(item, n=1, chests=None)` | [`lib.get.get(item, n)`](get.md#getitem-n1-radius32), which also gathers; `chests` is not used |
| `ensure_tool(kind, chests=None, upgrade=False)` | holds the best carried tool of `kind`, else [`lib.get.ensure_tool(kind)`](get.md#ensure_toolkind-uses1-tiernone-radius32); `chests` and `upgrade` are not used |
| `fetch(more, chests)` | [`lib.stock.fetch_all(more, chests)`](stock.md#fetch_allmore-chests) |

## `plan(item, n=1)`

[`needs`](../../crafting/needs.md)`(item, n)`, with the stations `craft_here` must craft for it planned first from the same items, through `needs`'s `also`: a crafting table where a step needs one (`needs`'s `table`) and a furnace where a step smelts, each only where the bot neither carries one nor finds one with [`find_nearest`](../../world/find_nearest.md) within `REACH` (4) blocks; a furnace takes a table to craft. A smoker or blast furnace within `REACH` stands in for the furnace where it has a recipe for every smelted input, as [`cooks_in`](../../crafting/cooks_in.md) tells: a smoker cooks kelp, but not chorus fruit, which only a furnace pops. So its `gather` is what the item and those stations lack together. It adds `no_tool`: `True` where a step smelts, no station within `REACH` cooks it and the bot carries no `#minecraft:pickaxes`, so the furnace `craft_here` would put down could not be mined back. A station just out of reach of `craft` or `smelt` but within that sphere is not planned for.

- **Returns** `needs`'s dict, with `no_tool`.
- **Pauses** no, unless `find_nearest` does.

The two halves `craft_here` and `smelt_here` use are in the module too: `place_beside(block)` places a carried block on the first of those spots that takes it and gives its [`Pos`](../../types/pos.md), `None` where no spot took it; `take_back(spot, block)` [`mine`](../../blocks/mine.md)s it and [`pick_up`](../../inventory/pick_up.md)s it where it did not fit.

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

To get the raw items as well, from chests or the world, use [`lib.get.get`](get.md#getitem-n1-radius32), and [`lib.get.ensure_tool`](get.md#ensure_toolkind-uses1-tiernone-radius32) for a tool.

## See also

[`needs`](../../crafting/needs.md), [`craft`](../../crafting/craft.md), [`smelt`](../../crafting/smelt.md), [`place`](../../blocks/place.md), [Containers and crafting](../../index.md#containers-and-crafting).
