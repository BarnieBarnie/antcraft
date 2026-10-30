# `lib/craft`

Crafting and smelting an item through its whole recipe tree, with a crafting table or furnace put down and taken back if need be. A module of the mod's default library: `import lib.craft`, then `lib.craft.craft_all("stone_pickaxe")` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/craft.py`, written with [`needs`](../../crafting/needs.md), [`craft`](../../crafting/craft.md) and [`smelt`](../../crafting/smelt.md); `obtain` and `ensure_tool` also take the raw items (and `ensure_tool` a finished tool) from chests with [`lib/inv`](inv.md)'s `take_all`.

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

Before it crafts anything it asks `plan(item, n)` (below) what to gather, the table and furnace it must craft included: with planks for the item or a table but not both, it crafts nothing and gives `missing_ingredients` with the logs both lack. Where a step smelts, no station within `REACH` cooks it (as for `plan`) and the bot carries no pickaxe, it crafts nothing and gives `no_tool`, as `smelt_here` would for the furnace it could not mine back.

- **Returns** what `craft_all` gives for the item, `missing_ingredients` with `plan`'s `gather` or `no_tool` before it crafts anything, or `no_table` (the steps before it made) where no spot beside the bot took a table.
- **Pauses** yes, as `craft_all` does, and while it mines the table back.

## `smelt_here(item, n=1, fuel=None)`

[`smelt`](../../crafting/smelt.md)`(item, n, fuel=fuel)` for a bot away from any furnace: `item` is the input, such as `"raw_iron"`. Where no furnace is within reach (`no_furnace`), it puts one down beside the bot as `craft_here` does a table: the furnace it carries, else one it first crafts from 8 carried cobblestone with `craft_here`. It plans that furnace with the `n` of `item` it smelts kept back, so `smelt_here("cobblestone", 8)` with only 8 cobblestone crafts no furnace from them: where the furnace and the input together lack raw items it crafts nothing and gives `missing_ingredients` with `missing`, what they lack. It smelts in it, then mines the furnace and picks it up again, with anything a failed smelt left in it, also when the smelt fails. With a furnace, or a blast furnace or smoker that cooks `item`, within reach it is `smelt` as is.

Mining the furnace back takes a pickaxe: where it would put one down and the bot carries no `#minecraft:pickaxes`, it places and crafts none and gives `no_tool`, with the inventory as it was.

- **Returns** a dict as `craft_all` gives: `ok`, or `smelt`'s reason (see [`Result`](../../types/result.md)), `no_tool` where the bot carries no pickaxe to mine the furnace back, `missing_ingredients` before it crafts anything, or `no_furnace` where the bot could not craft a furnace or no spot beside it took one.
- **Pauses** yes, as `smelt` does, and while it crafts, places and mines the furnace.

## `obtain(item, n=1, chests=None)`

Makes the bot carry `n` of `item`, raw items fetched from storage: where it carries `n` already it is done. Otherwise it asks `plan(item, n)` (below) what to gather, the raw items of a crafting table and furnace `craft_here` must craft included, and, where the chests and the bot together hold all of it, takes that from each chest of `chests` in turn (a list of [`Pos`](../../types/pos.md); `None`: the [`colony`](../../colony/colony.md)'s Colony Core, no chest for a bot in no colony), with [`lib/inv`](inv.md)'s `take_all`, so raw items spread over several chests are all used. Then it runs `craft_here(item, n)`, which crafts and puts a crafting table and furnace down as need be. That table or furnace stays carried.

- **Returns** a dict as `craft_all` gives: `ok`, or `missing_ingredients` with `missing`, item id to how many the chests lack, before it takes or crafts anything, or what `craft_here` gives.
- **Pauses** yes, while it walks to each chest and as `craft_here` does.

## `ensure_tool(kind, chests=None, upgrade=False)`

Makes the bot hold a tool of `kind`: `"pickaxe"`, `"axe"`, `"shovel"`, `"hoe"` or `"sword"`. Where it carries one, it [`equip`](../../inventory/equip.md)s the best: netherite, diamond, iron, stone, golden, then wooden, by the tier in the item id; a tool of another tier (from a mod) only where it carries none of these. Where it carries none, it [`take`](../../crafting/take.md)s the best finished one from `chests` (a list of positions; `None`: the colony's core, none outside a colony), by the same order, and holds it. Where the chests hold none, it runs `obtain(f"{tier}_{kind}", 1, chests)` for each tier from diamond, iron and stone down to wooden, stops at the first that works and holds that tool.

With `upgrade=True` it does the same where the bot carries a tool, but only for tiers better than the best one carried: a worse tool in a chest stays there, and where no better one can be taken or made it holds the one it carries. The worse tool is kept, a spare for when the better one breaks. Leave `upgrade` off in a script that calls `ensure_tool` every loop: an upgrade tries to make each better tier each time.

A tier the bot and the chests lack raw items for fails before anything is taken or crafted, so trying the better tiers first uses up nothing. The iron tier for a bot with no pickaxe and no furnace within reach also fails before crafting (`no_tool`), but what `obtain` took from the chests for it stays taken.

- **Returns** a dict as `craft_all` gives: `ok`, or, where the bot carries no tool of `kind` and none could be taken or made, what `obtain` gave for the wooden tool, such as `missing_ingredients`.
- **Pauses** yes, while it walks to a chest and as `obtain` does; not where the bot carries a tool of `kind` and `upgrade` is off.

## `plan(item, n=1)`

[`needs`](../../crafting/needs.md)`(item, n)`, with the stations `craft_here` must craft for it planned first from the same items, through `needs`'s `also`: a crafting table where a step needs one (`needs`'s `table`) and a furnace where a step smelts, each only where the bot neither carries one nor finds one with [`find_nearest`](../../world/find_nearest.md) within `REACH` (4) blocks; a furnace takes a table to craft. A smoker within `REACH` stands in for the furnace where every smelted input is in `#c:foods`, a blast furnace where every one is in `#c:ores` or `#c:raw_materials` (the module's `COOKS`); an item outside those tags that the station still cooks, such as kelp in a smoker, is planned for a furnace. So its `gather` is what the item and those stations lack together. A station just out of reach of `craft` or `smelt` but within that sphere is not planned for.

- **Returns** `needs`'s dict.
- **Pauses** no, unless `find_nearest` does.

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

A miner that makes sure it holds a pickaxe before it digs:

```python
import lib.craft

r = lib.craft.ensure_tool("pickaxe", chests=[memory["chest"]])
if not r["ok"]:
    warn(f"no pickaxe: {r['reason']} {r['missing']}")
```

## See also

[`needs`](../../crafting/needs.md), [`craft`](../../crafting/craft.md), [`smelt`](../../crafting/smelt.md), [`place`](../../blocks/place.md), [Containers and crafting](../../index.md#containers-and-crafting).
