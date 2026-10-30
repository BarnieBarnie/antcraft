# `lib/inv`

Taking what the bot lacks from a chest. A module of the mod's default library: `from lib.inv import take_all`, or `import lib.inv` and call `lib.inv.take_all(chest, need)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/inv.py`, written with `take`, `container`, `count` and `matches`; read it to see how.

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

## See also

[`blueprint`](../../building/blueprint.md), [Default colony scripts](../../../features/colony-scripts.md), [Modules & import](../../../language/modules.md#the-mods-library).
