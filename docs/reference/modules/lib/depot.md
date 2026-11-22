# `lib/depot`

A depot: a sorted storage row that grows. A row of chests from an origin towards a facing, each chest given one category (logs, saplings, apples, ...), and one more category, `other`, for the rest. When every chest of a category is full, the next chest of the row is placed for it. A module of the mod's default library: `import lib.depot`, then `lib.depot.store("wood")` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/depot.py`, a kit over [`lib/get`](get.md) (for a new chest), [`lib/storage`](storage.md) and [`lib/dig`](dig.md), written with [`put`](../../crafting/put.md), [`room`](../../crafting/room.md) and [`place`](../../blocks/place.md) (see [The script library](../../../design/library.md#layers)).

A depot is kept in [`memory`](../../types/memory.md)`["depots"][name]`, so it survives a restart, a reload and a respawn, and running the same job again goes on with the same chests.

```python
import lib.depot

lib.depot.depot("wood", Pos(10, 64, 10), "east", {
    "logs": ["#minecraft:logs"],
    "saplings": ["#minecraft:saplings"],
    "sticks": ["minecraft:stick"],
    "apples": ["minecraft:apple"],
})
r = lib.depot.store("wood")
if not r["ok"]:
    warn("depot:", r["reason"], r["missing"])
```

## `OTHER`

`"other"`, the category of an item no category selects.

## `depot(name, origin=None, facing="east", categories=None)`

The depot `name`, made the first time it is asked for: its row starts at `origin` (a [`Pos`](../../types/pos.md) or tuple) and goes one block at a time towards `facing` (`"north"`, `"south"`, `"east"` or `"west"`); `categories` is a dict of category name to a list of item ids or [selectors](../../types/selectors.md). Asked for again, it gives the depot it made, and `origin` and `facing` are not read: given `categories` replace the old ones, its chests stay as they are. **Returns** the depot, a dict with `origin`, `facing`, `categories`, `chests` (a list of `[category, pos]`, in row order) and, once a chest is added, `next` (the index of the next slot, past any skipped ones). **Pauses** no.

## `store(name, keep=None, radius=32)`

Puts what the bot carries into the depot's chests: each item into the chests of its category that have [`room`](../../crafting/room.md) for it, in row order. Where those are full, or the category has no chest yet, it puts a chest at the next slot of the row for the category (`add_chest`) and goes on there. It keeps what `keep` selects: a list of item ids or selectors, all of which it keeps, and of `(selector, n)` pairs, of which it keeps `n` in all and stores the rest, such as `("minecraft:dirt", 64)` (#762); `n` counts every item the selector selects together, so `("#minecraft:pickaxes", 1)` keeps one pickaxe, not one of each kind (#1010). The held item counts first: the item the bot holds when `store` begins it holds again after placing a chest, and a carried item is stored before the held one, so `("minecraft:fishing_rod", 1)` keeps the held rod (#1000). Of what falls in `other` it also keeps [`lib.storage.KEEP_KINDS`](storage.md#keep_kinds) (tools, weapons, torches, food) and chests, the chests for the next slot. An item a category selects is stored even where it is one of those, so an `apples` category takes the bot's apples. **Returns** a dict: `ok`, `reason` (`None`, or the reason adding a chest failed with, such as getting one or placing it), `at` (`None`) and `missing` (item id to how many the bot still carries beyond what it keeps). **Pauses** yes, while it walks to each chest.

## `make_room(name, free, keep=None, radius=32)`

Makes room for what a job gathers next (#765): where the bot has fewer than `free` empty slots ([`free_slots`](../../inventory/free_slots.md)), it stores what it carries (`store`, with `keep` and `radius`). **Returns** a dict: `ok` where it has `free` empty slots then, else `reason` (what `store` failed with, or `full` where it stored all it may and still has too few) and `missing`. A job that gets no `ok` stops gathering rather than have what it gathers dropped on the ground, as [`jobs.wood_farm`](../../../cookbook/wood-farm.md) stops felling. **Pauses** yes, while it stores.

## `chests(name, category=None)`

The positions of the depot's chests in row order, those of `category` only where it is given. **Pauses** no.

## `category(name, item)`

The first category of the depot whose selectors select `item` (an item id or a stack), `OTHER` where none does. **Pauses** no.

## `add_chest(name, cat, radius=32)`

Puts a chest at the depot's next slot and gives it to category `cat`. The chest faces along the row, so it never joins the chest next to it in a double chest. A chest already standing at the slot is taken as it is when it is empty or holds only items of `cat`. A slot that holds a chest of other items, or a block [`place`](../../blocks/place.md) may not replace (it fails `blocked`: dirt, stone, a log, an entity standing there), is skipped for good once a chest goes in a slot past it, and the next slot is tried (at most 8 skipped a call, else `blocked`, and `next` stays where it was, so calls on a blocked row never creep the depot along it, #765): the depot never digs, so it leaves what someone else put there. Otherwise the chest is got with [`lib.get.get`](get.md) within `radius`: one carried, one from a chest, or one crafted from planks, logs carried or stored. After a failed get, the next calls skip it and fail with its reason: 1 call, then 2, 4, doubling to 16, unless the bot carries the logs or planks for a chest, and a chest got ends it (#787), so a job that stores every round does not scan for a chest every round. **Returns** a dict: `ok`, `reason` (`None`, or what getting or placing the chest failed with) and `at` (the chest). **Pauses** yes.

`DepotGameTest` stores logs, saplings, sticks and apples each in a chest of its own, and fills a second logs chest, crafted from the logs, where the first is full; it skips a slot of dirt and one holding a chest of cobblestone, and on a row blocked past what a call skips leaves `next` where it was; with too few logs for a chest it backs off from getting one until it carries them. See also the cookbook page [Store and sort](../../../cookbook/store.md#a-depot-that-grows).
