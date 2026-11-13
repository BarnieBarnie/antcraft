# `lib/borrow`

Borrowing tools and items from chests and giving them back when the job is done, used or not. A module of the mod's default library: `from lib.borrow import borrow, borrowed, give_back` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/borrow.py`, written with `take`, `put`, `count` and `scan`; read it to see how.

What is out is kept in [`memory`](../../types/memory.md)`["borrowed"]`, so the record survives a script restart, a reload and a respawn: a job that borrowed and stopped halfway can give back from its next run. Library functions that take from a chest go through `borrow` (see [Writing library functions](../../index.md#writing-library-functions)).

## `borrow(chest, item, n=1)`

Takes up to `n` of `item`, an item id or a [selector](../../types/selectors.md), from the chest at `chest` with [`take`](../../crafting/take.md), and records the chest, the item and how many it took.

- **Returns** a [`result()`](result.md) dict: `ok` when it took all `n`; else the reason `take` failed with (such as `no_item` or `no_space`), `at` the chest and `missing` the item to how many it is short of. What it did take is recorded either way.
- **Pauses** yes, while it walks to the chest and takes.

## `borrowed()`

What is out, oldest first: a list of dicts with `chest`, `item` and `n`, as `borrow` recorded them. `[]` when nothing is.

- **Pauses** no.

## `give_back(radius=16)`

Puts each borrowed item back in the chest it came from. Where that chest is gone or full, it puts the rest in the nearest chest, trapped chest or barrel with room within `radius` blocks of it. A tool goes back as worn as it is. What the bot no longer carries, as it used it up or the tool broke, is struck off: of 4 borrowed planks it placed 2 of, it gives back 2. Items of the same kind the bot carried of its own count as borrowed.

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

## See also

[`lib/inv`](inv.md), [`take`](../../crafting/take.md), [`put`](../../crafting/put.md), [`memory`](../../types/memory.md).
