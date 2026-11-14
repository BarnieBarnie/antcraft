# `lib/dig`

Getting stone and ore out of the ground: a staircase down, a branch tunnel, the veins it uncovers. A module of the mod's default library: `import lib.dig` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/dig.py`, a skill written with [`lib/find`](find.md), [`lib/shaft`](shaft.md) and [`lib/mine`](mine.md).

Its doers assume the bot holds a pickaxe that drops what it digs (iron or better for diamond ore); without one, [`mine`](../../blocks/mine.md) fails or gets nothing. Each gives a [`result()`](result.md) dict: `ok`, `reason`, `at` and `missing`. Where it digs, it walls off a fluid or falling block beside what it digs, with cobblestone or another block of [`lib.shaft.FILL`](shaft.md) it carries, as [`lib/shaft`](shaft.md) does; air beside it it leaves open. `lib/shaft` and `lib/mine` stay for the laddered colony mine.

`facing` is `"north"`, `"south"`, `"east"` or `"west"`.

## `stone(n, radius=32)`

Mines stone or cobblestone within `radius` until the bot carries `n` more cobblestone: the exposed stone nearest at or above its feet first ([`lib.find.exposed`](find.md)), so it digs no pit where it need not, else the nearest stone at all. A block it cannot mine is skipped. Then it walks back to where it started with [`goto(start, keep="minecraft:cobblestone")`](../../movement/goto.md), out of a pit it may have dug, without pillaring with the cobblestone it came for.

- **Returns** a dict: `ok`, `reason` (`None`, `no_stone` with `missing` once no stone it can mine is left, or `no_space` with `at` where its inventory is full).
- **Pauses** yes, while it walks and mines.

## `ore(item, n, radius=32, y=None, facing="north", length=32)`

Makes the bot carry `n` more of `item`, a raw item: `coal`, `raw_iron`, `raw_copper`, `raw_gold`, `redstone`, `lapis_lazuli`, `diamond` or `emerald` (`minecraft:` may be left out). First it mines the exposed veins of its ore (the block tag `#c:ores/...`) within `radius`, nearest first, each whole through [`ore_vein`](../../mining/ore_vein.md), but for the blocks of a vein [`lib.find.WHERE`](find.md#where) leaves out, which stay. A vein its pickaxe does not drop ([`lib.tools.drops`](tools.md)), such as a modded ore in `#minecraft:needs_iron_tool` next to iron ore it mines with a stone pickaxe, it leaves, here and in the branches. Where they give too few, it digs `stairs_to(y)` towards `facing` (`y` is `best_y(item)` where `None`), digs up to 4 branches of `length` blocks on from there until it carries enough, and climbs back with `stairs_up()`. Where it dug no staircase, as for a bot already at `y`, or remembers one it is not on (see [`stairs()`](#stairs)), it walks back to where it started instead, so a bot in the Nether, which has no surface to dig up to, comes back too. Emerald has no `best_y`: pass `y`, or `ore` gives `no_ore` once the exposed veins are out. Where the staircase or a branch reaches the edge of [`lib.find.WHERE`](find.md#where), as in a colony, it turns square to the way it was going ([`turn`](#turnfacing-tried)) and goes on down or branches that way; it never turns back, which would dig under the steps of its staircase. Once every facing it turns to reaches the edge, it gives `no_ore` (#561).

- **Returns** a dict: `ok`, `reason` (`None`, `no_recipe` with `missing` for an item that is no ore, `no_ore` with `missing` where the branches found too little or there is no `y` to dig to, and `tier` where it left veins its pickaxe does not drop: the lowest pickaxe tier ([`lib.tools.tier_for`](tools.md#tier_forwhat)) that drops them all, which [`lib.get`](get.md) gets and tries again with, `no_space` with `at`, or the reason a step failed with `at`).
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

Digs a staircase one block wide from the bot's feet down to `y`, one block towards `facing` for each block down, and walks down it. Each step is three blocks tall, so the bot walks back up it, and it never digs straight down: the block under each step stays. It remembers the top in `memory["stairs"]`, and called again at the bottom of that staircase, or at the end of a branch from there, it goes on down and keeps the top. Where the bot stands at or below `y` it does nothing. It stops before a step with a block [`lib.find.WHERE`](find.md#where) leaves out.

- **Returns** a dict: `ok`, `reason` (`None`, `outside` with `at` the first block of the next step `WHERE` leaves out, or the reason a step failed, with `at`).
- **Pauses** yes, while it digs and walks.

## `turn(facing, tried)`

A facing square to `facing` (`east` or `west` for `north`) that is not in the list `tried`, or `None` where both are. `ore`, `stairs_up` and `jobs.strip_mine` turn with it where the way they go reaches the edge of [`lib.find.WHERE`](find.md#where), adding each facing that did to `tried`. Done at once.

## `stairs()`

The staircase `memory["stairs"]` remembers, a dict with its `top` and `bottom`, where the bot stands on or next to its bottom, or where a `branch` from there brought it, in the same dimension; else `None`, and it forgets the staircase, as one from before the bot was moved, died or did another job (#556). `stairs_to`, `stairs_up` and `ore` go by it, so none of them walks to an old staircase elsewhere. Done at once.

## `stairs_up(facing="north")`

Walks back up the staircase `stairs_to` dug, to its top, and forgets it. Where `memory` holds no staircase the bot is on ([`stairs()`](#stairs)), such as for a bot that came down another way, it digs one up towards `facing`, one block on for each block up, each step three blocks tall, until the bot stands where the block ahead of its feet is air and nothing above it blocks motion but leaves ([`surface`](../../world/surface.md)): the surface, not the floor of a tall cave. Where the block ahead is air with a floor under it, as on a cave's floor, it walks on along the floor instead of stepping up, so it needs no blocks to build its steps with; a pit ahead it fills with a block it carries or dug on the way (stone, cobblestone, netherrack and the like, `lib.shaft.FILL`), and where it has none it first digs one out of the wall beside or behind it. In a dimension with a roof over it, the Nether, `surface` gives `None` and it gives `no_surface` at once, without digging up into the roof. Where its next step has a block [`lib.find.WHERE`](find.md#where) leaves out, it climbs on square to the way it was going ([`turn`](#turnfacing-tried)), along the edge, and where every way it turns to is outside too it stops there with `outside` rather than dig out of WHERE (#561): the bot is still underground then, and the script decides whether to climb out by leaving WHERE.

- **Returns** a dict: `ok`, `reason` (`None`, `no_surface`, `no_blocks` where it has no block for a pit and no wall gives one, `outside` with `at` the block of the last step it tried that `WHERE` leaves out, that of [`goto`](../../movement/goto.md) with `at`, or the reason a step failed with `at`).
- **Pauses** yes, while it walks and digs.

## `branch(length, every=8, facing="north", left=None, out=None)`

Digs a tunnel two blocks tall `length` blocks on from the bot's feet towards `facing` and walks it, mining each ore vein in its walls and ceiling that its pickaxe drops (through `lib.mine.veins`; it leaves the others in the wall, and adds the tier each needs to the list `left` where given; the blocks of a vein `WHERE` leaves out stay too, each added to the list `out` where given) and putting a torch on the floor every `every` blocks while it carries one. It stops before a block [`lib.find.WHERE`](find.md#where) leaves out, so the tunnel stays within it. Branching from the bottom of a staircase `stairs_to` dug, it remembers in `memory["stairs"]` how far it got, so `stairs_up()` at its end still climbs that staircase.

- **Returns** a dict: `ok`, `reason` (`None`, `outside` with `at` the next block `WHERE` leaves out, or the reason it stopped, with `at`).
- **Pauses** yes, while it digs and walks.

```python
import lib.dig

lib.dig.stairs_to(lib.dig.best_y("raw_iron"))
lib.dig.branch(32)
lib.dig.stairs_up()
```

## See also

[`lib/find`](find.md), [`lib/mine`](mine.md), [`ore_vein`](../../mining/ore_vein.md), [`mine`](../../blocks/mine.md), [Mining](../../../features/mining.md).
