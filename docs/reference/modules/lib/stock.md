# `lib/stock`

The chests a bot may take from, which of them hold an item, and fetching an item from them. A module of the mod's default library: `from lib.stock import chests, where, fetch` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/stock.py`, written with `scan`, `container`, `count` and [`lib/borrow`](borrow.md); read it to see how.

What it takes from a chest is borrowed: [`lib/borrow`](borrow.md) records it, and `give_back()` returns what is left at the end of the job. What it takes from the bot's [Colony Core](../../colony/colony.md) is not: colony storage is the colony's own, so a builder or miner taking its materials there takes them for good, as `colony/common`'s `store` always did.

The colony store comes first (#1019): gatherers and builders take their toolkit and materials there before they take from another chest, craft or gather. From a store chest the bot borrows tools and weapons ([`lib.storage.KEEP_KINDS`](storage.md)), which `give_back()` returns there, and takes everything else for good, as from the Colony Core.

## `STORAGE`

Where the colony store is: a [`Pos`](../../types/pos.md), or `None` (the default). Set it as [`lib.find.WHERE`](find.md#where) is set, `lib.stock.STORAGE = Pos(10, 64, 10)`; a job's `storage x y z` argument sets it (`storage_arg`). Unset, a colony member uses the store its colony published, `colony_memory["storage"]` ([`lib.depot.publish_store`](depot.md)).

## `store()`

The chests of the colony store: where `STORAGE` is set, the chests, trapped chests and barrels within 8 blocks of it (a scan); else the chests of the published depot `colony_memory["storage"]` names; else none, as for a bot in no colony without `STORAGE`.

- **Returns** a list of [`Pos`](../../types/pos.md).
- **Pauses** no.

## `storage_arg(args)`

A job's command-line arguments without `storage x y z`, wherever it stands among them, which sets `STORAGE` to `Pos(x, y, z)`. The jobs that take from chests through `lib.get`, `lib.stock` or `lib.food` call it on their arguments (#1022): `jobs.get`, `jobs.build`, `jobs.strip_mine`, `jobs.ore_miner`, `jobs.bootstrap`, `jobs.settle`, `jobs.flatten`, `jobs.giant_tree`, `jobs.guard`, `jobs.explore`, `jobs.fish`, `jobs.ranch`, `jobs.farm`, `jobs.forester` and the farms `jobs.wood_farm`, `jobs.crop_farm`, `jobs.stem_farm`, `jobs.cane_farm`, `jobs.cactus_farm`, `jobs.bamboo_farm`, `jobs.berry_farm`, `jobs.cocoa_farm`, `jobs.kelp_farm`, `jobs.mushroom_farm` and `jobs.wart_farm`.

- **Returns** the list of arguments left.
- **Pauses** no.

## `chests(radius=32)`

The chests the bot may take from: the colony store's first (`store()`), whatever `radius` and `lib.find.WHERE` say, as the store may lie far away; then, nearest first, its colony's core (none for a bot in no colony), the positions listed in [`memory`](../../types/memory.md)`["chests"]`, and the chests, trapped chests and barrels within `radius` blocks. Each is listed once, and one in another [dimension](../../types/pos.md#dimension) than the bot's is left out, as is one [`lib.find.WHERE`](find.md#where) is false for (outside the colony, for a bot in one), asking [`colony()`](../../colony/colony.md) for it once a call, not once a chest (#631). With `scanning=False` it gives only the store, the core and `memory["chests"]`, without the scan of `radius`, which reads the sphere from a block budget all bots share; [`lib.get.ensure_tool`](get.md) looks there first.

- **Returns** a list of [`Pos`](../../types/pos.md); `[]` when there is none.
- **Pauses** no.

## `where(item, radius=32)`

The chests of `chests(radius)` that hold `item`, an item id or a [selector](../../types/selectors.md), in the order of `chests`: the store's first, then nearest first. For a cost estimate: a chest that holds an iron axe beats crafting one.

- **Returns** a list of [`Pos`](../../types/pos.md); `[]` when no chest holds it.
- **Pauses** no.

## `fetch(item, n, radius=32)`

Makes the bot carry `n` of `item` in total, taken from the chests of `where(item, radius)`, nearest first, each borrowed. What the bot carries counts: where it carries `n` already it takes nothing. Where the chests together hold too little, it takes nothing at all.

- **Returns** a [`result()`](result.md) dict: `ok`, or `no_item` with `missing`, the item to how many the chests lack, before it takes anything.
- **Pauses** yes, while it walks to each chest and takes; not where the bot carries `n` already or the chests hold too little.

```python
from lib.borrow import give_back
from lib.stock import fetch

r = fetch("#minecraft:planks", 10)
if r["ok"]:
    build()
give_back()
```

## `take_up_to(item, n, radius=32, chests=None)`

`fetch` without the all or nothing: takes `item` from the chests of `where(item, radius)`, nearest first, each borrowed, until the bot carries `n` in total or the chests hold no more, and keeps what it took. With `chests`, a list of [`Pos`](../../types/pos.md), it takes from those in turn instead, so `chests=[colony()["core"]]` takes from the Colony Core only, as the default colony scripts do. [`lib/get`](get.md)'s `get` takes with it, then gathers or crafts the rest. It replaces [`lib/inv`](inv.md)'s `take_all` and [`colony/common`](../colony/common.md)'s `fetch`, kept as wrappers of it.

- **Returns** a [`result()`](result.md) dict: `ok`, or `no_item` with `missing`, the item to how many the bot is still short.
- **Pauses** yes, while it walks to each chest and takes; not where the bot carries `n` already or no chest holds the item.

## `fetch_all(more, chests)`

The work of `fetch` for several items and given chests: takes `more`, item to how many more than the bot carries, from each of `chests` (a list of [`Pos`](../../types/pos.md)) in turn, borrowed but from the colony's core. Where the chests together hold too little of any item it takes nothing.

- **Returns** what is still short, item to how many; `{}` when nothing is.
- **Pauses** as `fetch`.

## See also

[`lib/borrow`](borrow.md), [`lib/inv`](inv.md), [`take`](../../crafting/take.md), [`scan`](../../world/scan.md).
