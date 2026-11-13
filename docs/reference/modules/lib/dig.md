# `lib/dig`

Getting stone and ore out of the ground: a staircase down, a branch tunnel, the veins it uncovers. A module of the mod's default library: `import lib.dig` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/dig.py`, a skill written with [`lib/find`](find.md), [`lib/shaft`](shaft.md) and [`lib/mine`](mine.md).

Its doers assume the bot holds a pickaxe that drops what it digs (iron or better for diamond ore); without one, [`mine`](../../blocks/mine.md) fails or gets nothing. Each gives a [`result()`](result.md) dict: `ok`, `reason`, `at` and `missing`. Where it digs, it walls off a fluid or falling block beside what it digs, with cobblestone or another block of [`lib.shaft.FILL`](shaft.md) it carries, as [`lib/shaft`](shaft.md) does; air beside it it leaves open. `lib/shaft` and `lib/mine` stay for the laddered colony mine.

`facing` is `"north"`, `"south"`, `"east"` or `"west"`.

## `stone(n, radius=32)`

Mines stone or cobblestone within `radius` until the bot carries `n` more cobblestone: the exposed stone nearest at or above its feet first ([`lib.find.exposed`](find.md)), so it digs no pit where it need not, else the nearest stone at all. A block it cannot mine is skipped. Then it walks back to where it started with [`goto(start, keep="minecraft:cobblestone")`](../../movement/goto.md), out of a pit it may have dug, without pillaring with the cobblestone it came for.

- **Returns** a dict: `ok`, `reason` (`None`, `no_stone` with `missing` once no stone it can mine is left, or `no_space` with `at` where its inventory is full).
- **Pauses** yes, while it walks and mines.

## `ore(item, n, radius=32, y=None, facing="north", length=32)`

Makes the bot carry `n` more of `item`, a raw item: `coal`, `raw_iron`, `raw_copper`, `raw_gold`, `redstone`, `lapis_lazuli`, `diamond` or `emerald` (`minecraft:` may be left out). First it mines the exposed veins of its ore (the block tag `#c:ores/...`) within `radius`, nearest first, each whole through [`ore_vein`](../../mining/ore_vein.md). Where they give too few, it digs `stairs_to(y)` towards `facing` (`y` is `best_y(item)` where `None`), digs up to 4 branches of `length` blocks on from there until it carries enough, and climbs back with `stairs_up()`. Emerald has no `best_y`: pass `y`, or `ore` gives `no_ore` once the exposed veins are out.

- **Returns** a dict: `ok`, `reason` (`None`, `no_recipe` with `missing` for an item that is no ore, `no_ore` with `missing` where the branches found too little or there is no `y` to dig to, `no_space` with `at`, or the reason a step failed with `at`).
- **Pauses** yes, while it walks and digs.

```python
import lib.dig

r = lib.dig.ore("raw_iron", 3)
if not r["ok"]:
    say(f"no iron: {r['reason']} {r['missing']}")
```

## `best_y(item)`

The height where the ore of the raw item `item` is most common: coal 96, raw iron 16, raw copper 48, raw gold -16, redstone and diamond -59, lapis lazuli 0; `None` for any other, emerald too: it is only in mountains, high up, which a height alone does not find. Done at once.

## `stairs_to(y, facing="north")`

Digs a staircase one block wide from the bot's feet down to `y`, one block towards `facing` for each block down, and walks down it. Each step is three blocks tall, so the bot walks back up it, and it never digs straight down: the block under each step stays. It remembers the top in `memory["stairs"]`, and called again at the bottom of that staircase it goes on down and keeps the top. Where the bot stands at or below `y` it does nothing.

- **Returns** a dict: `ok`, `reason` (`None`, or the reason a step failed, with `at`).
- **Pauses** yes, while it digs and walks.

## `stairs_up(facing="north")`

Walks back up the staircase `stairs_to` dug, to its top, and forgets it. Where `memory` holds no staircase, such as for a bot that came down another way, it digs one up towards `facing`, one block on for each block up, each step three blocks tall, until the bot stands where the block ahead of its feet is air and none is above its head for 16 blocks: the surface. A cave that tall passes for it.

- **Returns** a dict: `ok`, `reason` (`None`, that of [`goto`](../../movement/goto.md) with `at`, or the reason a step failed with `at`).
- **Pauses** yes, while it walks and digs.

## `branch(length, every=8, facing="north")`

Digs a tunnel two blocks tall `length` blocks on from the bot's feet towards `facing` and walks it, mining each ore vein in its walls and ceiling (through `lib.mine.veins`) and putting a torch on the floor every `every` blocks while it carries one.

- **Returns** a dict: `ok`, `reason` (`None`, or the reason it stopped, with `at`).
- **Pauses** yes, while it digs and walks.

```python
import lib.dig

lib.dig.stairs_to(lib.dig.best_y("raw_iron"))
lib.dig.branch(32)
lib.dig.stairs_up()
```

## See also

[`lib/find`](find.md), [`lib/mine`](mine.md), [`ore_vein`](../../mining/ore_vein.md), [`mine`](../../blocks/mine.md), [Mining](../../../features/mining.md).
