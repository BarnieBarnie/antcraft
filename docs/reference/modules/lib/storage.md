# `lib/storage`

Putting away and sorting: what the bot carries into chests, each item with its kind, and a few chests sorted so each item sits in one. A module of the mod's default library: `import lib.storage`, then `lib.storage.deposit()` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/storage.py`, a skill over [`lib/stock`](stock.md), [`lib/borrow`](borrow.md) and [`lib/inv`](inv.md), written with [`put`](../../crafting/put.md), [`take`](../../crafting/take.md) and [`container`](../../crafting/container.md) (see [The script library](../../../design/library.md#layers)).

```python
import lib.storage

r = lib.storage.deposit(keep=["cobblestone"])
if not r["ok"]:
    say("no room for " + str(r["missing"]))
```

The bundled job `jobs.store [radius]` deposits (radius 32 by default) and walks home, warning `store: no_space` with what it kept where the chests are full: `/antcraft bot run jobs.store 16`.

## `KEEP_KINDS`

What `deposit` never puts away: the tools and food of [`lib.inv.KEPT`](inv.md) (pickaxes, axes, shovels, hoes, swords, `#c:foods`), bows, crossbows, tridents, maces and torches. A list of selectors; add to it, or pass `keep`.

## `deposit(keep=None, radius=32)`

First gives back what the bot borrowed ([`give_back`](borrow.md)), so a borrowed item goes back to its own chest rather than to any chest. Then puts everything else it carries, but `KEEP_KINDS` and what `keep` (a list of item ids or selectors) selects, into the chests of [`lib.stock.chests(radius)`](stock.md) (its colony's core, `memory["chests"]` and the chests within `radius`): each item first into the chests that already hold it, nearest first, then into the other chests, nearest first, until it carries none. **Returns** a dict: `ok`, `reason` (`None`, or `no_space` where some did not fit), `at` (`None`) and `missing` (item id to how many the bot still carries). **Pauses** yes, while it walks to each chest. A full chest is found full only once the bot has walked to it.

## `sort(chests)`

Moves the items of `chests` (a list of positions) so each item sits in as few of them as it can: all of an item into the chest that holds most of it, as far as that one has room; what does not fit goes back where it came from. It carries one item at a time, so the bot needs a free slot or two. One target per item: an item its target has no room for stays split over the rest. **Returns** a dict like `deposit`'s, `missing` naming what the bot was left carrying as no chest took it back. **Pauses** yes.

`StorageGameTest` tests `deposit` with a chest that already holds the item and one that does not, with full chests, `sort` over two chests, and the job.
