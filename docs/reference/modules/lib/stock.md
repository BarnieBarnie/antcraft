# `lib/stock`

The chests a bot may take from, which of them hold an item, and fetching an item from them. A module of the mod's default library: `from lib.stock import chests, where, fetch` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/stock.py`, written with `scan`, `container`, `count` and [`lib/borrow`](borrow.md); read it to see how.

What it takes from a chest is borrowed: [`lib/borrow`](borrow.md) records it, and `give_back()` returns what is left at the end of the job. What it takes from the bot's [Colony Core](../../colony/colony.md) is not: colony storage is the colony's own, so a builder or miner taking its materials there takes them for good, as `colony/common`'s `fetch` and `store` always did.

## `chests(radius=32)`

The chests the bot may take from, nearest first: its colony's core (none for a bot in no colony), the positions listed in [`memory`](../../types/memory.md)`["chests"]`, and the chests, trapped chests and barrels within `radius` blocks. Each is listed once, and one in another [dimension](../../types/pos.md#dimension) than the bot's is left out, as is one [`lib.find.WHERE`](find.md#where) is false for (outside the colony, for a script that imports [`colony/common`](../colony/common.md)).

- **Returns** a list of [`Pos`](../../types/pos.md); `[]` when there is none.
- **Pauses** no.

## `where(item, radius=32)`

The chests of `chests(radius)` that hold `item`, an item id or a [selector](../../types/selectors.md), nearest first. For a cost estimate: a chest that holds an iron axe beats crafting one.

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

## `take_up_to(item, n, radius=32)`

`fetch` without the all or nothing: takes `item` from the chests of `where(item, radius)`, nearest first, each borrowed, until the bot carries `n` in total or the chests hold no more, and keeps what it took. [`lib/get`](get.md)'s `get` takes with it, then gathers or crafts the rest.

- **Returns** a [`result()`](result.md) dict: `ok`, or `no_item` with `missing`, the item to how many the bot is still short.
- **Pauses** yes, while it walks to each chest and takes; not where the bot carries `n` already or no chest holds the item.

## `fetch_all(more, chests)`

The work of `fetch` for several items and given chests: takes `more`, item to how many more than the bot carries, from each of `chests` (a list of [`Pos`](../../types/pos.md)) in turn, borrowed but from the colony's core. Where the chests together hold too little of any item it takes nothing. [`lib/craft`](craft.md)'s `obtain` fetches its raw items with it.

- **Returns** what is still short, item to how many; `{}` when nothing is.
- **Pauses** as `fetch`.

## See also

[`lib/borrow`](borrow.md), [`lib/inv`](inv.md), [`take`](../../crafting/take.md), [`scan`](../../world/scan.md).
