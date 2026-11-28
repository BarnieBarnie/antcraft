# `lib/dig`

Getting stone and ore out of the ground: a staircase down, a branch tunnel, the veins it uncovers. A module of the mod's default library: `import lib.dig` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/dig.py`, a skill written with [`lib/find`](find.md), [`lib/shaft`](shaft.md) and [`lib/mine`](mine.md).

Its doers assume the bot holds a pickaxe that drops what it digs (iron or better for diamond ore); without one, [`mine`](../../blocks/mine.md) fails or gets nothing. Each gives a [`result()`](result.md) dict: `ok`, `reason`, `at` and `missing`. Where it digs, it walls off a fluid or falling block beside what it digs, with cobblestone or another block of [`lib.shaft.FILL`](shaft.md) it carries, as [`lib/shaft`](shaft.md) does; air beside it it leaves open. `lib/shaft` and `lib/mine` stay for the laddered colony mine.

`facing` is `"north"`, `"south"`, `"east"` or `"west"`.

## `stone(n, radius=32)`

Mines stone or cobblestone within `radius` until the bot carries `n` more cobblestone: the exposed stone nearest at or above its feet first ([`lib.find.exposed`](find.md)), so it digs no pit where it need not, else the nearest stone at all. A block it cannot mine is skipped. Then it walks back to where it started with [`goto(start, keep="minecraft:cobblestone")`](../../movement/goto.md), out of a pit it may have dug, without pillaring with the cobblestone it came for.

- **Returns** a dict: `ok`, `reason` (`None`, `no_stone` with `missing` once no stone it can mine is left, or `no_space` with `at` where its inventory is full).
- **Pauses** yes, while it walks and mines.

## `ore(item, n, radius=32, y=None, facing="north", length=32)`

Makes the bot carry `n` more of `item`, a raw item: `coal`, `raw_iron`, `raw_copper`, `raw_gold`, `redstone`, `lapis_lazuli`, `diamond` or `emerald` (`minecraft:` may be left out). First it mines the exposed veins of its ore (the block tag `#c:ores/...`) within `radius`, nearest first, each whole through [`ore_vein`](../../mining/ore_vein.md), but for the blocks of a vein [`lib.find.WHERE`](find.md#where) leaves out, which stay. A vein its pickaxe does not drop ([`lib.tools.drops`](tools.md)), such as a modded ore in `#minecraft:needs_iron_tool` next to iron ore it mines with a stone pickaxe, it leaves, here and in the branches. Where they give too few, it digs [`spiral_to(y)`](#spiral_toy-facingnorth) towards `facing` (`y` is `best_y(item)` where `None`), digs up to [`rounds(item, n)`](#roundsitem-n) branches of `length` blocks on from there until it carries enough, and climbs back with `stairs_up()`. Where it dug no staircase, as for a bot already at `y`, or remembers one it is not on (see [`stairs()`](#stairs)), it walks back to where it started instead, so a bot in the Nether, which has no surface to dig up to, comes back too. Emerald has no `best_y`: pass `y`, or `ore` gives `no_ore` once the exposed veins are out. Where the staircase reaches the edge of [`lib.find.WHERE`](find.md#where), as in a colony, it turns clockwise and spirals on down; where a branch does, it turns square to the way it was going ([`turn`](#turnfacing-tried)) and branches that way; it never turns back, which would dig under the steps of its staircase, nor digs the floor of any step, in its way or in the veins of its ceiling ([`guarded`](#behindlegs-bottom-and-guardedlegs-where)). Once the staircase has no room to spiral on, or every facing a branch turns to reaches the edge, it gives `no_ore` (#561, #1009).

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

## `branches(item, blocks)`

How many branches of 32 `ore` digs on average at `best_y(item)` to find `blocks` blocks of the ore of `item`, with no limit, so a trip that gives `no_ore` counts too, as the next one digs on for the rest. From `BRANCHES`, the branches for 1, 4, 8, 16 and 32 blocks, measured with `scripts/ore_sim.py`, a Monte Carlo of the game's ore features through a branch whose walls and ceiling are mined as `ore` mines them (#985, #989):

| item | ore a branch finds | none in a branch | 1 block | 4 | 8 | 16 | 32 |
|---|---|---|---|---|---|---|---|
| `diamond` | 1.01 | 79% | 5.0 | 7.2 | 11.7 | 20.4 | 37.9 |
| `raw_iron` | 3.16 | 62% | 2.6 | 2.9 | 3.9 | 6.5 | 11.4 |
| `raw_gold` | 1.07 | 85% | 7.5 | 7.7 | 12.2 | 20.2 | 35.9 |
| `redstone` | 2.33 | 64% | 3.0 | 3.2 | 5.3 | 8.9 | 16.1 |
| `lapis_lazuli` | 0.71 | 85% | 7.6 | 9.0 | 16.6 | 30.8 | 56.6 |
| `raw_copper` | 4.00 | 61% | 2.5 | 2.5 | 3.1 | 5.3 | 9.3 |
| `coal` | 15.31 | 52% | 2.1 | 2.1 | 2.1 | 2.1 | 3.5 |

In between it goes along the line between two counts, within a tenth of the simulation, and past 32 along the line from 16 to 32. A vein holds several blocks, so a few cost about as many branches as one: gold finds 4 in 7.7 branches, where the line through 1 and 12 blocks it had before #989 gave 9.5. One for any other (emerald: not measured). Ponytail: through solid rock; with a tenth of it caves (`--air 0.1`, caves as small balls) the rarer ores take some 10 to 20% more branches, as no ore is in a cave and some ores are left out next to air, but caves also expose veins `ore` mines first. [`lib.get`](get.md) costs an ore's trip with it. Done at once.

## `rounds(item, n)`

How many branches `ore` digs at most for `n` more of `item` before it gives `no_ore`: more than twice `branches(item, blocks(item, n))`, at least 4 (`ROUNDS`). So it falls short of one diamond some 9 times in 100 and of six some 10 (39 and 70 with the 4 it dug before #985), 11 and 19 branches, and of any ore at most some 11 times in 100. 20 redstone are 5 blocks, 8 branches, and with a Fortune III pickaxe 4 blocks, 7 branches (#988, #989). Done at once.

## `blocks(item, n)`

How many ore blocks drop `n` of `item`, by the mean drops of its ore with the Fortune of the best pickaxe carried, as [`lib.get`](get.md)'s `Source.blocks` counts them: `ORE_DROPS` for copper (3.5 times `ore_drops`), lapis (6.5 times) and redstone (4.5 and half the Fortune level more), else `ore_drops(level)`, the mean of the game's ore bonus (1, 1.33, 1.75 and 2.2 for Fortune 0 to 3). `lib.get` takes `FORTUNE`, `ore_drops` and `ORE_DROPS` from here (#988). Done at once.

## `stairs_to(y, facing="north")`

Digs a staircase one block wide from the bot's feet down to `y`, one block towards `facing` for each block down, and walks down it. Each step is three blocks tall, so the bot walks back up it, and it never digs straight down: the block under each step stays. It remembers the top in `memory["stairs"]`, and called again at the bottom of that staircase, or at the end of a branch from there, it goes on down and keeps the top. Where the bot stands at or below `y` it does nothing. It stops before a step with a block [`lib.find.WHERE`](find.md#where) leaves out, and at a block it may not mine (bedrock, a barrier, a protected area), which it gives `outside` for too, as that edge (#732).

- **Returns** a dict: `ok`, `reason` (`None`, `outside` with `at` the first block of the next step `WHERE` leaves out, or the reason a step failed, with `at`).
- **Pauses** yes, while it digs and walks.

## `spiral_to(y, facing="north")`

`stairs_to(y, facing)`, turning clockwise (`north`, `east`, `south`, `west`) each time a leg stops with `outside` at the edge of [`lib.find.WHERE`](find.md#where), so in a small area the staircase spirals down as deep as it must, where `turn` runs out of facings after four legs (#1009). It stops where a turned leg has no room for a single step, as a second turn there would dig back under the last steps. `ore`, `jobs.strip_mine` and `jobs.ore_miner` dig their stairs with it.

- **Returns** `stairs_to`'s dict, with `facing`, the way of the last leg, and `legs`, a list of `(start, facing)` for each leg: `reason` is `outside` where it could spiral no deeper.
- **Pauses** yes, while it digs and walks.

## `behind(legs, bottom)` and `guarded(legs, where)`

`behind` gives the floor, a `Pos`, of every step of the stairs `spiral_to` dug in `legs`, down to `bottom`. `guarded` gives a [`lib.find.WHERE`](find.md#where) that is `where` but for those floors of the stairs down to the bot, so a branch that turns back towards the stairs at the edge stops at the last two steps rather than dig under them, and the vein in a branch's ceiling stops at the floor of an earlier leg above it, either of which would break the way back up (#1014, #1017). `ore` and `jobs.strip_mine` set it while they branch; `jobs.ore_miner` keeps `behind`'s floors in its own WHERE. `keyed(cells)` gives `cells` as a dict with `True` for each, so the check costs one lookup a block however deep the stairs. It asks `where` once a position, so keep it to branches, not searches.

## `turn(facing, tried)`

A facing square to `facing` (`east` or `west` for `north`) that is not in the list `tried`, or `None` where both are. The branches of `ore` and `jobs.strip_mine`, and `stairs_up`, turn with it where the way they go reaches the edge of [`lib.find.WHERE`](find.md#where), adding each facing that did to `tried`. Done at once.

## `stairs()`

The staircase `memory["stairs"]` remembers, a dict with its `top` and `bottom`, where the bot stands on or next to its bottom, or where a `branch` from there brought it, in the same dimension; else `None`, and it forgets the staircase, as one from before the bot was moved, died or did another job (#556). `stairs_to`, `stairs_up` and `ore` go by it, so none of them walks to an old staircase elsewhere. Done at once.

## `stairs_up(facing="north")`

Walks back up the staircase `stairs_to` dug, to its top, and forgets it. Where `memory` holds no staircase the bot is on ([`stairs()`](#stairs)), such as for a bot that came down another way, it digs one up towards `facing`, one block on for each block up, each step three blocks tall, until the bot stands where the block ahead of its feet is air and nothing above it blocks motion but leaves ([`surface`](../../world/surface.md)): the surface, not the floor of a tall cave. Where the block ahead is air with a floor under it, as on a cave's floor, it walks on along the floor instead of stepping up, so it needs no blocks to build its steps with; a pit ahead it fills with a block it carries or dug on the way (stone, cobblestone, netherrack and the like, `lib.shaft.FILL`), and where it has none it first digs one out of the wall beside or behind it. In a dimension with a roof over it, the Nether, `surface` gives `None` and it gives `no_surface` at once, without digging up into the roof. Where its next step has a block [`lib.find.WHERE`](find.md#where) leaves out, it climbs on square to the way it was going ([`turn`](#turnfacing-tried)), along the edge, and where every way it turns to is outside too it stops there with `outside` rather than dig out of WHERE (#561): the bot is still underground then, and the script decides whether to climb out by leaving WHERE.

- **Returns** a dict: `ok`, `reason` (`None`, `no_surface`, `no_blocks` where it has no block for a pit and no wall gives one, `outside` with `at` the block of the last step it tried that `WHERE` leaves out, that of [`goto`](../../movement/goto.md) with `at`, or the reason a step failed with `at`).
- **Pauses** yes, while it walks and digs.

## `branch(length, every=8, facing="north", left=None, out=None)`

Digs a tunnel two blocks tall `length` blocks on from the bot's feet towards `facing` and walks it, mining each ore vein in its walls and ceiling that its pickaxe drops (through `lib.mine.veins`; it leaves the others in the wall, and adds the tier each needs to the list `left` where given; the blocks of a vein `WHERE` leaves out stay too, each added to the list `out` where given) and putting a torch on the floor every `every` blocks while it carries one. It stops before a block [`lib.find.WHERE`](find.md#where) leaves out, so the tunnel stays within it, and at a block it may not mine (bedrock, a barrier, a protected area), which it gives `outside` for too, as that edge, so `ore` turns there (#732). Branching from the bottom of a staircase `stairs_to` dug, it remembers in `memory["stairs"]` how far it got, so `stairs_up()` at its end still climbs that staircase.

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
