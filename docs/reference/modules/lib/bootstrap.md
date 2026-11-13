# `lib/bootstrap`

Kits: the things a job starts with, got from whatever is around. A module of the mod's default library: `import lib.bootstrap`, then `lib.bootstrap.kit()` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/bootstrap.py`, written with [`lib/get`](get.md), the resolver, and only jobs import it (see [The script library](../../../design/library.md#kits-libbootstrap)).

What a kit takes from a chest is borrowed: end the job with [`give_back()`](borrow.md), also when it fails, as [`jobs/bootstrap`](../../../cookbook/bootstrap.md) does.

```python
import lib.bootstrap
from lib.borrow import give_back

MINER = [("stone_pickaxe", 1), ("torch", 32)]
try:
    r = lib.bootstrap.kit(MINER)
    if r["ok"]:
        say("ready to dig")
finally:
    give_back()
```

## `KIT`

The default kit, a list of `(item, n)` in the order `kit` gets them, the tools first so the rest is gathered with them:

```python
[("stone_pickaxe", 1), ("stone_axe", 1), ("stone_shovel", 1), ("stone_sword", 1), ("crafting_table", 1),
 ("furnace", 1), ("torch", 16)]
```

## `kit(items=KIT, radius=32)`

Makes the bot carry each of `items`, a list of `(item, n)`, in order, with what is within `radius`: [`get(item, n, radius)`](get.md#getitem-n1-radius32) for each. A tool, an item named `<tier>_<kind>` such as `"stone_pickaxe"`, it gets with [`ensure_tool(kind, 1, tier, radius)`](get.md) instead, so a carried or chest tool of that kind and at least that tier does: with an iron pickaxe in a chest, `("stone_pickaxe", 1)` borrows it rather than making a stone one.

An item whose recipe burns coal, such as a torch, gets charcoal smelted from logs where `get` finds that cheaper than coal: with no coal ore exposed within `radius` and none in a chest (see [`get`](get.md#getitem-n1-radius32)).

- **Returns** a [`result()`](result.md) dict: `ok` once the bot carries every item; else the reason the first item that failed gave, with `missing`. What it got before it keeps.
- **Pauses** yes, while it gathers, crafts and smelts.

An empty bot by four oaks and two dozen stone gets the whole `KIT` in about 2000 ticks (`BootstrapGameTest`).
