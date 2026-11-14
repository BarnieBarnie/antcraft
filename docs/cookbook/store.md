# Store and sort

A job that puts away what the bot carries and walks home: each item into a chest that already holds it, the rest into any chest with room, nearest first, keeping its tools, weapons, torches and food. The mod bundles it as `jobs/store.py`; start it with `/antcraft bot run jobs.store 16` for the chests within 16 blocks (32 by default). Run it at the end of a trip, or from your own script once the bot's inventory fills up.

```python
import lib.storage
from lib.home import go_home

r = lib.storage.deposit(radius=int(ARGS[0]) if len(ARGS) > 0 else 32)
go_home()
if not r["ok"]:
    warn("store:", r["reason"], r["missing"])
```

## How it works

- [`deposit`](../reference/modules/lib/storage.md) first gives back what the bot borrowed ([`give_back`](../reference/modules/lib/borrow.md)), so a borrowed pickaxe goes back to its own chest.
- The chests are those of [`lib.stock.chests`](../reference/modules/lib/stock.md): the colony's core, the positions in `memory["chests"]` and the chests and barrels within the radius. For each item the bot carries, the chests that hold it come first, read from afar with [`container`](../reference/crafting/container.md), then the others.
- [`room`](../reference/crafting/room.md) tells from afar how many of the item a chest still takes, so the bot skips a full chest instead of walking to it to find out. [`put`](../reference/crafting/put.md) then walks to each chest with room until the bot carries none of the item.
- What fits nowhere stays with the bot and is named in `missing`, which the job warns about after [`go_home`](../reference/modules/lib/home.md).

## Sort a few chests

Point the bot at the chests of a store room and [`sort`](../reference/modules/lib/storage.md) gathers each item into the chest that holds most of it, taking from the others only what that chest has room for:

```python
import lib.storage

chests = scan("chest", radius=6)
r = lib.storage.sort(chests)
if not r["ok"]:
    say("no room for " + str(r["missing"]))
```

The bot carries one item at a time, so it needs a free slot or two.

`StorageGameTest` runs the bundled job in a test area as `jobs.store 8`, and tests `deposit` with a chest that holds the item, a full chest it skips without walking to it, chests too full for anything, and `sort` over two chests.

## See also

- [`lib/storage`](../reference/modules/lib/storage.md), [`lib/stock`](../reference/modules/lib/stock.md), [`room`](../reference/crafting/room.md), [`container`](../reference/crafting/container.md), [`put`](../reference/crafting/put.md)
- [Keep a chest stocked](stock-chest.md), which fills a chest instead of emptying the bot
