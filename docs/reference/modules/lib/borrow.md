# `lib/borrow`

Borrowing tools and items from chests and giving them back when the job is done, used or not. A module of the mod's default library: `from lib.borrow import borrow, borrowed, give_back, keep` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/borrow.py`, written with `take`, `put`, `count` and `scan`; read it to see how.

What is out is kept in [`memory`](../../types/memory.md)`["borrowed"]`, so the record survives a script restart, a reload and a respawn: a job that borrowed and stopped halfway can give back from its next run. Library functions that take from a chest go through `borrow` (see [Writing library functions](../../index.md#writing-library-functions)).

## `borrow(chest, item, n=1)`

Takes up to `n` of `item`, an item id or a [selector](../../types/selectors.md), from the chest at `chest` with [`take`](../../crafting/take.md), and records the chest, the item, how many it took and how many of it the bot carried before.

- **Returns** a [`result()`](result.md) dict: `ok` when it took all `n`; else the reason `take` failed with (such as `no_item` or `no_space`), `at` the chest and `missing` the item to how many it is short of. What it did take is recorded either way.
- **Pauses** yes, while it walks to the chest and takes.

## `borrowed()`

What is out, oldest first: a list of dicts with `chest`, `item`, `n` and `had`, how many of the item the bot carried before it borrowed (its own and what it borrowed earlier), as `borrow` recorded them. A record from an older version has no `had`. `[]` when nothing is.

- **Pauses** no.

## `give_back(radius=16, since=0)`

Puts each borrowed item back in the chest it came from. Where that chest is gone or full, it puts the rest in the nearest chest, trapped chest or barrel with room within `radius` blocks of it. A tool goes back as worn as it is. What the bot no longer carries, as it used it up or the tool broke, is struck off: of 4 borrowed planks it placed 2 of, it gives back 2. It gives back only what the bot carries above what it had before it borrowed, so what it used counts as borrowed first and its own items stay with it: a bot that owned 3 planks, borrowed 4 and used 5 gives back none and keeps its 2. A record without `had` counts every carried item of its kind as borrowed.

With `since`, it gives back only the records from index `since` of `borrowed()` on and leaves the older ones out: a function that notes `len(borrowed())` as it starts gives back only what it borrowed itself, as [`get`](get.md) does when it fails.

- **Returns** a [`result()`](result.md) dict: `ok`, or `no_space` with `missing`, item to how many the bot kept as no chest took them. Those stay in `borrowed()`, for a later `give_back`.
- **Pauses** yes, while it walks to the chests and puts; not when the bot carries nothing it borrowed.

```python
from lib.borrow import borrow, give_back

borrow(chest, "iron_pickaxe")
borrow(chest, "#minecraft:planks", 4)
mine(ore)
place(spot, "oak_planks")
r = give_back()
if not r["ok"]:
    say(f"no chest takes back {r['missing']}")
```

## `keep(item, n=None)`

Strikes `n` of `item` off what is borrowed, newest first, or all of it when `n` is `None`, for a job that keeps what it took: `give_back` then leaves it with the bot. `item` is matched against the item `borrow` was given, with or without `minecraft:`; a selector also matches each item id it [selects](../../inventory/matches.md), so `keep("#minecraft:planks", 2)` keeps 2 of borrowed `oak_planks`. A record borrowed by a selector is matched only by the same selector.

- **Returns** `None`.
- **Pauses** no.

```python
from lib.borrow import borrow, give_back, keep

borrow(chest, "iron_pickaxe")
borrow(chest, "oak_planks", 4)
keep("iron_pickaxe")  # the job's result
give_back()            # the planks go back, the pickaxe stays
```

## See also

[`lib/inv`](inv.md), [`take`](../../crafting/take.md), [`put`](../../crafting/put.md), [`memory`](../../types/memory.md).
