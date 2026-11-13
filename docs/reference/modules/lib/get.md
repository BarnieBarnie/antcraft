# `lib/get`

The resolver: get anything, and the tool to get it with, by the cheapest way. A module of the mod's default library: `from lib.get import get, ensure_tool`, then `get("stone_axe")` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/get.py`, written with [`lib/stock`](stock.md), [`lib/craft`](craft.md), [`lib/tools`](tools.md), [`lib/cost`](cost.md), [`lib/trees`](trees.md) and [`lib/dig`](dig.md).

The skills below it assume what they need: [`lib.dig.stone`](dig.md#stonen-radius32) a pickaxe, [`lib.craft.craft_here`](craft.md) the raw items. `lib/get` is where those needs are met, so a script asks for what it wants and the chain of who fetches what lives in one place (see [The script library](../../../design/library.md#the-resolver-libget)). What it takes from a chest is borrowed: end a job with [`give_back()`](borrow.md).

```python
from lib.get import get
from lib.borrow import give_back

r = get("stone_pickaxe")
if r["ok"]:
    get("cobblestone", 64)
give_back()
```

## `get(item, n=1, radius=32)`

Makes the bot carry at least `n` of `item`, an item id such as `"stone_axe"` or `"minecraft:raw_iron"` (any log for a log, such as `"oak_log"`: planks come from any). Called again, it does nothing. It tries, in turn:

1. what the bot carries: where it carries `n`, it is done;
2. the chests of [`lib.stock.chests(radius)`](stock.md#chestsradius32): it [takes](stock.md#take_up_toitem-n-radius32) as many as they hold, up to `n` with what the bot carries, borrowed (from the Colony Core taken), and gets only the rest by the steps below; so with 5 planks in a chest, `get("oak_planks", 8)` takes the 5 and crafts the rest from a log;
3. `SOURCES[item]`, for a raw item: it makes the bot hold the source's tool with `ensure_tool` (below), then runs the source for what is short;
4. crafting: [`lib.craft.plan(item, n)`](craft.md) lists the raw items, the crafting table and furnace included; `get` gets each, chests first as above, plans again (at most 5 rounds, as getting a tool for one raw item can use up another), then [`lib.craft.craft_here(item, n)`](craft.md). Where the plan smelts and the bot carries no pickaxe to mine the furnace back, it gets one first. Where the plan wants coal, as fuel or for a torch, and no chest within `radius` holds any, it gets logs instead where they cost less than the coal source (see [`wood_for_coal`](#wood_for_coalk-radius)) and plans again: carried logs burn, and charcoal smelted from them stands in for coal. So with no coal ore exposed within `radius`, a bot smelts with wood rather than dig stairs for one coal.

So an empty bot by a tree and some stone gets a stone axe: logs by hand, a wooden pickaxe from them, cobblestone with it, then the axe.

- **Returns** a [`result()`](result.md) dict: `ok`, or a reason with `missing`, the item to how many it lacks:

| Reason | When |
|---|---|
| `no_recipe` | the item has no source and no recipe makes it, such as an elytra, and no chest holds enough |
| `cycle` | getting the item waits on getting itself, or more than `DEPTH` (8) gets wait on each other |
| `no_tool` | no tool of the kind a source needs can be had (see `ensure_tool`) |
| another reason | that of the source, the chest or `craft_here` that failed, such as `no_logs` or `no_stone` |

- **Pauses** yes, while it walks, gathers and crafts; not where the bot carries `n` already.

## `ensure_tool(kind, uses=1, tier=None, radius=32)`

Makes the bot hold a tool of `kind` (`"pickaxe"`, `"axe"`, `"shovel"`, `"hoe"` or `"sword"`) that lasts `uses` more blocks and is at least `tier` (`"wooden"`, `"stone"`, `"iron"`, ...; `None`: any). With `tier="hand"` no tool is needed, as logs and dirt break by hand: it gets one only where it is cheaper than the hand. It takes the cheapest of `options(kind, uses, tier, radius)` (below): the carried ones where they last, [`hold`](tools.md)ing the best; a chest's tool that lasts with the carried ones, borrowed; or the cheapest tier to make, made with `get` and held. So felling a few trees takes a stone axe, a forest an iron one, and a chest's iron axe beats making either. Where a way fails it tries the next: a bot with logs and no stone in reach makes a wooden pickaxe once the stone one fails `no_stone`.

- **Returns** a [`result()`](result.md) dict: `ok`, `no_tool` with `missing` where there is no way (a tier no source can get the raw items of, or one whose recipe waits on an item being got), or the reason `get` failed with for the last tier it tried to make.
- **Pauses** yes, while it walks to a chest or makes the tool; not where a carried tool lasts, or the hand is cheapest.

## `choose(kind, uses=1, tier=None, radius=32)`

The cheapest of `options(kind, uses, tier, radius)`, a tuple `(ticks, how, tier, at)`; `None` where there is none. A finder: it takes and makes nothing, so a script can ask what `ensure_tool` would do. One call costs some 27,000 instructions (14 ticks at the default budget) for an empty bot with no chest near, most of it [`lib.craft.plan`](craft.md) of each tier's tool, each of which asks `needs` (3000); each tier plans another item, so nothing is planned twice (#522).

```python
from lib.get import choose

choose("axe", 30)[1:3]    # ('make', 'stone')
choose("axe", 400)[1:3]   # ('make', 'iron'): it wears out four stone axes and breaks slower
```

## `options(kind, uses=1, tier=None, radius=32)`

Each way to have a tool of `kind` for `uses` blocks of at least `tier`, cheapest first, each a tuple `(ticks, how, tier, at)`:

| `how` | `tier` | `at` | When |
|---|---|---|---|
| `"hand"` | `None` | `None` | `tier` is `"hand"` |
| `"carried"` | the best carried | `None` | the carried tools of `kind` of at least `tier` last `uses` blocks together |
| `"chest"` | the tool's | the chest, a [`Pos`](../../types/pos.md) | a tool in one of `lib.stock.chests(radius)` lasts the `uses` the carried tools of `kind` of at least `tier` leave: a worn one is used up first, so with a stone pickaxe of 20 uses carried a chest's of 20 more does for 30 |
| `"make"` | one of `lib.craft.MADE` | how many to carry in all | the raw items of `plan` for that many each have a source |

`ticks` estimates the work, with the numbers of [`lib/cost`](cost.md): [`breaking`](cost.md)`(uses, material, tier)` for every way (material `"stone"` for a pickaxe, `"wood"` for an axe, `"dirt"` for a shovel, none for a hoe or sword), plus the walk to a chest, or for `"make"` the estimate of each raw item `plan` lists for as many tools as `uses` wears out, and `SMELT` for each item smelted. Carried items count: a bot with the cobblestone and a log makes a stone axe for nothing but the breaking.

ponytail: rough estimates, not a simulation; the uses of a carried tool of another tier do not count toward making one, the make cost takes a source's `estimate`, not its `ticks(radius)`, so it does not look whether the ore is exposed, and the tool a raw item's source needs is not counted in the make cost.

## `SOURCES`

How `get` gathers each raw item: a dict of item id (`"#minecraft:logs"` for any log) to a `Source`. It starts with:

| Item | How | Tool |
|---|---|---|
| any log | [`lib.trees.logs`](trees.md) | axe, or by hand |
| cobblestone | [`lib.dig.stone`](dig.md#stonen-radius32) | pickaxe |
| dirt, sand | the exposed ones within `radius`, nearest first | shovel, or by hand |
| coal, raw iron, raw copper, raw gold, redstone, lapis lazuli, diamond, emerald | [`lib.dig.ore`](dig.md) | pickaxe of [`lib.tools.tier_for`](tools.md) its ore |

An ore source's pickaxe tier comes from the block it is about to mine: before it digs, `get` finds the nearest exposed block of the source's `ore` and asks [`lib.tools.tier_for`](tools.md#tier_forwhat) of its position, which reads the block's `needs_*_tool` tags, so a modded ore that needs an iron pickaxe gets one. Where none is exposed it uses the source's `tier`. ponytail: only that first block's tier counts; a vein further on that needs more is tried with the same pickaxe.

A script adds a source for anything the library cannot get yet, or replaces one, by assigning to it; `get` uses it from then on, also for the items it crafts from it:

```python
from lib.get import get, SOURCES, Source
from lib.result import result
import lib.find

def clay(n, radius):
    return result(mine(lib.find.nearest("clay", radius)).reason)  # a clay block drops 4

SOURCES["minecraft:clay_ball"] = Source(clay, tool="shovel", tier="hand")
get("clay")   # a clay block, crafted from the 4 clay balls
```

## `Source(do, tool=None, tier=None, estimate=None, ore=None)`

How to gather a raw item. `do(n, radius)` gets `n` more of it within `radius` blocks and gives a [`result()`](result.md); `get` calls it with the tool held. `tool` is the kind of tool it needs (`None`: none), `tier` the lowest tier that drops the item (`None`: any; `"hand"`: the bare hand does too, so a tool is only got where it pays). `estimate` is the ticks one takes, for `options`; `None` gives `ESTIMATE` (100). `ore` is the block or tag of the ore it digs, such as `"#c:ores/coal"` for coal's: its `ticks(radius)` is `estimate`, or `DEEP` (1000) where none of `ore` is exposed within `radius` blocks, so [`lib.dig.ore`](dig.md) would dig stairs.

## `wood_for_coal(k, radius)`

How many logs `get` gets instead of `k` coal: `k` to smelt into charcoal and two thirds as many to burn doing it, which as planks also burn as long as the coal would. `0` where `k` is 0, a chest within `radius` holds coal, or the coal source's `ticks(radius)` for `k` is less than the logs' plus `SMELT` for each charcoal. A finder, done at once.

## `key(item)`

The item id `get` and `SOURCES` use for `item`: the full id, `"minecraft:"` added where it has no namespace, and `"#minecraft:logs"` for a log without a source of its own. Done at once.

## See also

[`lib/stock`](stock.md), [`lib/craft`](craft.md), [`lib/tools`](tools.md), [`lib/cost`](cost.md), [`lib/borrow`](borrow.md), [`lib/gather`](gather.md), [`needs`](../../crafting/needs.md).
