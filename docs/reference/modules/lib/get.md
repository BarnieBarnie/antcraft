# `lib/get`

The resolver: get anything, and the tool to get it with, by the cheapest way. A module of the mod's default library: `from lib.get import get, ensure_tool`, then `get("stone_axe")` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/get.py`, written with [`lib/stock`](stock.md), [`lib/craft`](craft.md), [`lib/tools`](tools.md), [`lib/cost`](cost.md), [`lib/trees`](trees.md) and [`lib/dig`](dig.md).

The skills below it assume what they need: [`lib.dig.stone`](dig.md#stonen-radius32) a pickaxe, [`lib.craft.craft_here`](craft.md) the raw items. `lib/get` is where those needs are met, so a script asks for what it wants and the chain of who fetches what lives in one place (see [The script library](../../../design/library.md#the-resolver-libget)). What it takes from a chest is borrowed: end a job with [`give_back()`](borrow.md).

To keep it within an area, set [`lib.find.WHERE`](find.md#where) to a function of a [`Pos`](../../types/pos.md): every chest it takes from, every tree, stone, ore and dirt it gathers, and every cost `choose` and `Source.cost` estimate leave out the positions it is false for. [`colony/common`](../colony/common.md) sets it to `in_colony`, so a colony script that imports it neither borrows from a neighbour's chest nor fells a tree outside the colony.

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
2. the chests of [`lib.stock.chests(radius)`](stock.md#chestsradius32): it [takes](stock.md#take_up_toitem-n-radius32-chestsnone) as many as they hold, up to `n` with what the bot carries, borrowed (from the Colony Core taken), and gets only the rest by the steps below; so with 5 planks in a chest, `get("oak_planks", 8)` takes the 5 and crafts the rest from a log;
3. `SOURCES[item]`, for a raw item: it makes the bot hold the source's tool with `ensure_tool` (below), then runs the source for what is short; where the source gives a `tier` with its failure, as [`lib.dig.ore`](dig.md#oreitem-n-radius32-ynone-facingnorth-length32)'s `no_ore` does for the veins its pickaxe did not drop, it makes the bot hold a tool of that tier and runs the source once more for the rest, so a bot with a stone pickaxe that branched past diamonds gets an iron pickaxe and mines them;
4. crafting: [`lib.craft.plan(item, n)`](craft.md) lists the raw items, the crafting table and furnace included. Before it gathers any, it takes what the plan crafts or smelts on the way from the same chests, borrowed (from the Colony Core taken), the last step first, and plans again, so it gathers only what they do not cover: with spruce planks in a chest and no tree, `get("spruce_trapdoor")` borrows the planks, and with sticks in a chest a wooden pickaxe takes them rather than more planks from logs. ponytail: it takes a step's exact item, as many as the step makes, so oak planks the plan picked are not birch planks a chest holds. Then `get` gets each raw item, chests first as above, plans again (at most 5 rounds, as getting a tool for one raw item can use up another), then [`lib.craft.craft_here(item, n)`](craft.md). Where the plan smelts and the bot carries no pickaxe to mine the furnace back, it gets one first. Where the plan wants coal, as fuel or for a torch, more than the chests within `radius` hold, it gets logs for the rest instead where they cost less than the coal source (see [`wood_for_coal`](#wood_for_coalk-radius)), smelts them into charcoal at once and plans again, so the coal it takes from the chests after is not burnt making the charcoal. So with no coal ore exposed within `radius`, a bot smelts with wood rather than dig stairs for one coal, and with 1 coal in a chest 8 torches take that coal and one charcoal (#564).

So an empty bot by a tree and some stone gets a stone axe: logs by hand, a wooden pickaxe from them, cobblestone with it, then the axe.

- **Returns** a [`result()`](result.md) dict: `ok`, or a reason with `missing`, the item to how many it lacks:

| Reason | When |
|---|---|
| `no_recipe` | the item has no source and no recipe makes it, such as an elytra, and no chest holds enough |
| `cycle` | getting the item waits on getting itself, or more than `DEPTH` (8) gets wait on each other |
| `no_tool` | no tool of the kind a source needs can be had (see `ensure_tool`) |
| another reason | that of the source, the chest or `craft_here` that failed, such as `no_logs` or `no_stone` |

Where it fails, it [gives back](borrow.md#give_backradius16-since0) what it borrowed during the call, from a chest or for a tool, so a failed `get` leaves the chests as it found them: with 5 planks in a chest and no log in reach, `get("oak_planks", 8)` takes the 5, fails for the rest and puts the 5 back. What was borrowed before the call stays borrowed, and a nested `get` (a raw item, a tool) that failed has given its own back already. What it made from borrowed items, such as planks from a borrowed log, the bot keeps; the log is struck off as used. That is on purpose: undoing a craft is not possible, and the planks are as good to the next job as the log. Where the script errors or is stopped in the middle of `get`, it gives back nothing itself: an error goes on to the caller, whose `try`/`finally` around its job gives back (as the bundled jobs do), and after a stop the records stay in `borrowed()`, kept in memory, for the next run's `give_back()`.

- **Pauses** yes, while it walks, gathers and crafts; not where the bot carries `n` already.

## `ensure_tool(kind, uses=1, tier=None, radius=32)`

Makes the bot hold a tool of `kind` (`"pickaxe"`, `"axe"`, `"shovel"`, `"hoe"` or `"sword"`) that lasts `uses` more blocks and is at least `tier` (`"wooden"`, `"stone"`, `"iron"`, ...; `None`: any). With `tier="hand"` no tool is needed, as logs and dirt break by hand: it gets one only where it is cheaper than the hand. Where the carried ones of at least `tier` last, it [`hold`](tools.md)s the best at once; with `tier` `None` or `"hand"` a carried tool of no listed tier (a modded one, or a copper one) counts too, as it does for [`lib.tools.best`](tools.md#bestkind), but not where a tier is asked, as its tier is not known. Else, unless `tier` is `"hand"`, it takes the first tool that lasts with them in the nearest of `lib.stock.chests(radius)` that holds one, where the [walk](cost.md) there is at most `NEAR` (80 ticks, some 16 blocks; borrowed, but from the colony's core). A farther chest's tool it weighs against making one in `options`: with logs carried, a wooden axe made beats a stone axe 30 blocks off. Where no tool can be made for want of a raw item, as no log is within `radius` (the source's `there`) and no chest holds logs, planks or sticks, it fails at once with that source's reason, `no_logs`, rather than cost the options first (4 ticks for an empty bot, 114 before, #562). Only then does it cost the ways to make one, `options(kind, uses, tier, radius)` (below, some 100 ticks in a colony), and take the cheapest way, made with `get` and held (#551). So felling a few trees takes a stone axe, a forest an iron one, and a chest's iron axe beats making either. Where a way fails it tries the next: a bot with logs and no stone in reach makes a wooden pickaxe once the stone one fails `no_stone`. A hoe's `uses` are the blocks it tills: tilling takes as long with any hoe, so only the cost of getting one counts, and a carried or chest hoe that lasts beats making one, a wooden one from logs where none does (`colony.farmer` gets its hoe this way).

- **Returns** a [`result()`](result.md) dict: `ok`, `no_tool` with `missing` where there is no way (a tier no source can get the raw items of, or one whose recipe waits on an item being got), the reason of the source of a raw item every tool needs where none is within `radius` (`no_logs`), or the reason `get` failed with for the last tier it tried to make.
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

`ticks` estimates the work, with the numbers of [`lib/cost`](cost.md): [`breaking`](cost.md)`(uses, material, tier)` for every way (material `"stone"` for a pickaxe, `"wood"` for an axe, `"dirt"` for a shovel, none for a hoe or sword), plus the walk to a chest, or for `"make"` the [`cost(k, radius)`](#sourcedo-toolnone-tiernone-estimatenone-orenone-nearnone-ynone) of each raw item's source for the `k` that `plan` lists for as many tools as `uses` wears out, `SMELT` for each item smelted, and the tools those sources need: for each kind, one of the highest tier any of them needs (wooden for any tier; an ore source's tier from its nearest exposed block), free where the bot carries tools of that kind and tier that last them all, else the walk to the nearest chest of [`lib.stock.chests(radius)`](stock.md#chestsradius32) with one that lasts the rest, else the make cost of one. Coal costs the walk to the nearest chest within `radius` that holds any, as `get` takes it from there first, for as much as the chests within `radius` hold; the rest, or all where none holds any, the cheaper of its source and the logs [`wood_for_coal`](#wood_for_coalk-radius) would get instead, smelted, as `get` burns wood where coal is deep; with wood, coal needs no pickaxe. So an iron axe costs an empty bot a stone pickaxe for the raw iron, itself made with a wooden pickaxe (or the walk to a chest that holds one), and the trip down to iron's depth once where no iron ore is exposed within `radius`. Carried items count: a bot with the cobblestone and a log makes a stone axe for nothing but the breaking, and one with a stone pickaxe pays no pickaxe for raw iron.

Where `tier` is `"hand"` or the carried tools last, neither of which can fail, a tier dearer to make than them is left out: `make_cost` stops as soon as the cost passes theirs, a source costing at least its estimate each, so for a few logs a diamond or iron axe costs no ore scan and no pickaxe (some 100 ticks less for the colony lumberjack before each tree, #582).

ponytail: rough estimates, not a simulation; the uses of a carried tool of another tier do not count toward making one. One `options` call costs some 48,000 instructions (some 26 ticks) for an empty bot with no chest near, most of it [`plan`](craft.md) of each tool and pickaxe tier.

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

## `Source(do, tool=None, tier=None, estimate=None, ore=None, near=None, y=None)`

How to gather a raw item. `do(n, radius)` gets `n` more of it within `radius` blocks and gives a [`result()`](result.md); `get` calls it with the tool held. `tool` is the kind of tool it needs (`None`: none), `tier` the lowest tier that drops the item (`None`: any; `"hand"`: the bare hand does too, so a tool is only got where it pays). `estimate` is the ticks one takes, for `options` (through `cost`); `None` gives `ESTIMATE` (100). `ore` is the block or tag of the ore it digs, such as `"#c:ores/coal"` for coal's, and `y` the height `do` digs down to for it where none is exposed, for `cost`. `near` is the block or tag `do` gets the item from, where none within `radius` means there is nothing to get (`None`: not known): the log, dirt and sand sources set it, so where the colony has no tree `get` calls `do` at once, without a tool, and it fails at once (`no_logs`) instead of after costing and making an axe or the tools a pickaxe needs (#554). Its `there(radius)` says whether one is in reach (always where `near` is `None`). `cost` and `make_cost` do not look at it: they cost the item as if there were some.

Its `cost(n, radius)` is a tuple `(ticks, tier)`, a finder: `ticks` is `estimate` for each of `n`, and the trip `deep(y)` once more where none of `ore` is exposed within `radius` blocks, so [`lib.dig.ore`](dig.md) would dig stairs down to `y`, branch and climb back: once for the trip, however many it gets. The trip is measured: with a stone pickaxe through stone some 48 ticks a step down (`STEP_DOWN`), 37 a block of branch (`BRANCH`) and 10 a step back up (`STEP_UP`), for the steps from the bot's feet down to `y` and one branch of 32 ([`lib.dig.LENGTH`](dig.md)). The ore sources take `y` from [`lib.dig.best_y`](dig.md), so from sea level that is about 1200 for coal (96: no stairs), 4000 for iron (16) and 8300 for diamond (-59), and diamond costs more than coal from the same height (#564). With coal's 1200, up to 4 coal (16 torches) are charcoal from logs where no coal ore is in sight, and more are dug for. A source with an `ore` and no `y`, such as one a script adds, costs `DEEP` (2000); `tier` is the pickaxe tier [`lib.tools.tier_for`](tools.md#tier_forwhat) the nearest exposed block of `ore` needs, where the source takes a pickaxe and one is exposed, else the source's `tier`. `get` holds a tool of that tier, and `options` costs one.

## `wood_for_coal(k, radius)`

How many logs `get` gets instead of `k` coal, less the coal the chests of [`lib.stock`](stock.md) within `radius` hold (#564): that many to smelt into charcoal and two thirds as many to burn doing it, which as planks also burn as long as the coal would. `0` where the chests hold all `k`, or the coal source's `cost` for the rest is less than the logs' plus `SMELT` for each charcoal. A finder, done at once.

## `key(item)`

The item id `get` and `SOURCES` use for `item`: the full id, `"minecraft:"` added where it has no namespace, and `"#minecraft:logs"` for a log without a source of its own. Done at once.

## See also

[`lib/stock`](stock.md), [`lib/craft`](craft.md), [`lib/tools`](tools.md), [`lib/cost`](cost.md), [`lib/borrow`](borrow.md), [`lib/gather`](gather.md), [`needs`](../../crafting/needs.md).
