# `lib/gather`

Gathering the raw items of early tools and making an item from nothing. A module of the mod's default library: `import lib.gather`, then `lib.gather.make("stone_axe")` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/gather.py`, written with [`lib/craft`](craft.md), [`lib/trees`](trees.md), [`scan`](../../world/scan.md), [`mine`](../../blocks/mine.md) and [`goto`](../../movement/goto.md).

Where [`lib/craft`](craft.md)'s `obtain` takes raw items from chests, this module gets them from the world, so a bot with an empty inventory near a tree and some stone can make a stone axe.

## `make(item, n=1, radius=32)`

Makes the bot carry `n` more of `item`: gathers with `gather` the raw items [`lib.craft.plan(item, n)`](craft.md) lists, plans again and gathers again (at most 5 rounds) until nothing is missing, then crafts it with [`lib.craft.craft_here(item, n)`](craft.md), a crafting table put down and taken back as need be.

- **Returns** a dict: `ok`, `reason`, `at` and `missing`, those of the first `gather` that failed, else those of `craft_here`.
- **Pauses** yes, while it gathers and crafts.

```python
import lib.gather

r = lib.gather.make("stone_axe")
if not r["ok"]:
    say(f"no stone axe: {r['reason']} {r['missing']}")
```

## `gather(item, n=1, radius=32)`

Makes the bot carry `n` more of a raw item it knows how to get within `radius` blocks:

- a log (`#minecraft:logs` or any `..._log` id; any log does, as a plan's oak log only goes into planks): through [`lib.trees.logs(n, radius)`](trees.md), one tree at a time, its bottom logs from the ground, so it never climbs a tree;
- cobblestone: through `stone(n, radius)`.

Any other item fails at once with `no_recipe`.

- **Returns** a dict: `ok`, `reason` (`None`, `no_recipe` for an item it does not know how to get, or the reason of `lib.trees.logs` or `stone`), `at` and `missing` (the items it still lacks).
- **Pauses** yes, while it walks and mines.

## `stone(n, radius=32)`

Mines stone or cobblestone within `radius` until the bot carries `n` more cobblestone, the nearest at or above its feet first so it digs no pit where it need not (`exposed_stone`). Where it carries no pickaxe it first makes a wooden one with `make`. A block it cannot mine is skipped. Then it walks back to where it started with [`goto(start, keep="minecraft:cobblestone")`](../../movement/goto.md), out of a pit it may have dug, without pillaring with the cobblestone it came for.

- **Returns** a dict: `ok`, `reason` (`None`, `no_stone` with `missing` once no stone it can mine is left, `no_space` with `at` where its inventory is full, or that of `make` for the pickaxe).
- **Pauses** yes, while it walks and mines.

## `exposed_stone(radius=32, skip=None)`

The nearest stone or cobblestone within `radius` at or above the bot's feet, else the nearest at all, leaving out the positions in the dict `skip`; `None` if there is none.

## `is_log(item)`

Whether the item id or selector `item` means a log to `gather`. Done at once.

## See also

[`lib/craft`](craft.md), [`lib/trees`](trees.md), [`needs`](../../crafting/needs.md).
