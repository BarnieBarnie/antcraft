# `lib/inv`

Taking what the bot lacks from a chest, and putting the rest away. A module of the mod's default library: `from lib.inv import take_all, store_all`, or `import lib.inv` and call `lib.inv.take_all(chest, need)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/inv.py`, written with `take`, `put`, `container`, `inventory`, `count` and `matches`; read it to see how.

## `take_all(chest, need)`

Takes from the chest at `chest` until the bot carries, of each item in `need`, as many as `need` gives: `need` maps an item id or a [selector](../../types/selectors.md) such as `"#minecraft:planks"` to how many the bot should carry. What the bot already carries counts, so an item it carries in full is not taken; an item the chest has none of is skipped, and of one the chest has too little it takes what there is.

- **Returns** what is still short, item to how many: `{}` when the bot carries everything in `need`.
- **Pauses** yes, while it walks to the chest and takes; not when the bot carries enough already or the chest has none of what it lacks.

```python
from lib.inv import take_all

short = take_all(chest, {"minecraft:cobblestone": 16, "#minecraft:planks": 8})
for item, n in short.items():
    say(f"the chest lacks {n} {item}")
```

`colony.builder` takes a blueprint's materials from the Colony Core with it, and `colony.miner` the ladders, torches and chests of its shaft; [`colony/common`](../colony/common.md)'s `fetch(item, n)` is `take_all` of one item at the core.

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
