# `lib/inv`

Putting what the bot carries away. A module of the mod's default library: `from lib.inv import store_all`, or `import lib.inv` and call `lib.inv.store_all(chest)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/inv.py`, written with `take`, `put`, `container`, `inventory`, `count` and `matches`; read it to see how.

## `take_all(chest, need)`

Kept for old scripts: [`lib.stock.take_up_to(item, n, chests=[chest])`](stock.md#take_up_toitem-n-radius32-chestsnone) for each item to `n` of `need`, which takes from the chest until the bot carries `n` of the item or the chest has no more, borrowed but from the Colony Core.

- **Returns** what is still short, item to how many: `{}` when the bot carries everything in `need`.
- **Pauses** as `take_up_to`.

## `store_all(chest, keep=None)`

Puts everything the bot carries into the chest at `chest`, item by item with [`put`](../../crafting/put.md), but its tools (`#minecraft:pickaxes`, `#minecraft:axes`, `#minecraft:shovels`, `#minecraft:hoes` and `#minecraft:swords`), its food (`#c:foods`) and what `keep` selects: a list of item ids or [selectors](../../types/selectors.md), such as `["torch", "#minecraft:logs"]`. The kept kinds are the module's `KEPT` list.

- **Returns** a dict as [`dig_shaft`](shaft.md#dig_shaftlevels) gives: `ok`, `reason`, `at` (always `None`) and `missing`.
- **Pauses** yes, while it walks to the chest and puts; not when the bot carries nothing to put away.

| Reason | When |
|---|---|
| `no_space` | the chest filled up; `missing` maps each item it did not put away, in full or in part, to how many the bot still carries |
| another reason | the reason `put` failed with, such as `target_gone` (see [`Result`](../../types/result.md)) |

```python
from lib.inv import store_all

r = store_all(chest, keep=["torch"])
if r["reason"] == "no_space":
    say("the chest is full")
```

`lib/mine`'s `unload` keeps its own rules: it drops junk and keeps blocks to fill with.

## See also

[`blueprint`](../../building/blueprint.md), [Default colony scripts](../../../features/colony-scripts.md), [Modules & import](../../../language/modules.md#the-mods-library).
