# `lib/mine`

Mining the levels of the shaft [`lib/shaft`](shaft.md) dug. A module of the mod's default library: `import lib.mine`, then `lib.mine.mine_level(20)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/mine.py`, written with [`mineshaft_at`](../../mining/mineshaft_at.md) and [`ore_vein`](../../mining/ore_vein.md). How it digs, what it keeps and what goes into the chest is on [Mining](../../../features/mining.md#libminepy); this page lists the functions.

It works on the shaft in `memory["shaft"]`. How many rounds of a level are done is kept in `memory["mine"]` (level to rounds); how far a round is dug is read from the world, so a call again goes on where it stopped.

## `mine_level(level, rounds=1)`

Digs the hall of the level at height `level` and its next `rounds` rounds of branches, mines every ore vein it uncovers that its pickaxe drops ([`lib.tools.drops`](tools.md)), up to the edge of [`lib.find.WHERE`](find.md#where) (`veins`), puts torches at the level's torch spots, and puts the ore into the landing chest, also whenever its inventory fills. The stone and other junk it does not keep it drops where it stands rather than store it, so the chest never fills with junk and the level can be mined round after round; dropped items vanish after 5 minutes, as a player's do. Where water or lava is in or beside the next block, it seals it, stops that hall or branch and goes on with the next. A hall or branch stops short of the first block `lib.find.WHERE` leaves out too, as [`lib.dig.branch`](dig.md#branchlength-every8-facingnorth-leftnone-outnone) does, and it goes on with the next (#593), so a colony miner's tunnels end at the colony's edge.

- **Returns** a dict as [`dig_shaft`](shaft.md#dig_shaftlevels) gives (`ok`, `reason`, `at`, `missing`) `sealed`, the fluid blocks where a hall or branch stopped, and `outside`, the first block `lib.find.WHERE` leaves out of each hall or branch that stopped at its edge (#620), and each block of a vein it left there (#626), each block once (#632), empty where none did.
- **Pauses** yes, while it digs, walks and puts away.

Without a shaft in memory that has such a level, it stops the script with an assertion error: call `lib.shaft.dig_shaft` first.

| Reason | When |
|---|---|
| `missing_ingredients` | no pickaxe (checked first), or nothing to seal a fluid with; `missing` says which |
| `no_space` | the landing chest is full of ore, or putting away frees too little room |
| another reason | an action's (see [`Result`](../../types/result.md)), with `at` |

## `veins(cells, opened, stand, chest=None, left=None, out=None)`

Mines every ore vein ([`ore_vein`](../../mining/ore_vein.md)) in the walls and ceiling of `cells`, standing at `stand`, and walls off what each opens up; `opened` are the blocks that stay open. `mine_level` and [`lib.dig.branch`](dig.md#branchlength-every8-facingnorth-leftnone-outnone) mine the veins of their tunnels with it. A vein its pickaxe does not drop ([`lib.tools.drops`](tools.md)) stays in the wall, and the tier that drops it is added to the list `left` where given. The blocks of a vein [`lib.find.WHERE`](find.md#where) leaves out stay too, so a vein across the edge of a colony is mined only up to it; each is added to the list `out` where given (#561), once, though the vein is met again from the next cells (#632). `mine_level` keeps to it too, so a colony miner mines a vein only up to the colony's edge, a column ([`in_colony`](../colony/common.md#in_colonyp), #576), and its halls and branches stop at that edge too (#593). With a `chest`, it empties the bot into it when its inventory is nearly full.

- **Returns** `None` once done, else a result dict with the reason it stopped and `at`.
- **Pauses** yes, while it digs.

## `site(r=0)`

The shaft in `memory["shaft"]` with its branches of round `r`, as `mineshaft_at` gives it; `None` without a shaft in memory. Besides `top` and `levels`, `memory["shaft"]` may hold `branches` (per side, default 4) and `length` (of a branch, default 16) for a shaft of another size.

## `tier_at(y, ores=())`

The pickaxe tier the ores common at height `y` need: the best [`lib.tools.tier_for`](tools.md) of the ores of `SPREAD` and of `ores` (ore block ids, such as modded ones) whose [`spread`](#spreadore) holds `y`, `"wooden"` where none is. `"stone"` at 16, where iron is the best of them; `"iron"` below -8, where gold, redstone and diamonds are. `SPREAD` lists the vanilla ores only, each with the heights it is common at, from the game's ore spread. Pass a modded ore in `ores` and `tier_for` gives its tier from its `needs_*_tool` tags, at every height unless you add it to `SPREAD` with its heights in your script. Else `colony.miner` takes a tier per level from `colony_memory["mineshaft"]["tiers"]` where this is not right.

## `spread(ore)`

`(lowest y, highest y)` where the ore block `ore` (`"iron_ore"` or `"minecraft:iron_ore"`) is common: its entry in `SPREAD`, else `DEFAULT_SPREAD`, `(-64, 320)`, every height of the Overworld, so an ore `SPREAD` does not list, such as a modded one, counts at any height. Done at once.

## Example

Mine two rounds of level 20, then bring up what is in the chest:

```python
import lib.shaft
import lib.mine

r = lib.mine.mine_level(20, rounds=2)
if not r["ok"]:
    warn(f"mining stopped: {r['reason']} at {r['at']}")
chest = lib.mine.site()["level"][20]["chest"]
take(chest, "minecraft:raw_iron")
lib.shaft.to_surface()
```

## See also

[`lib/shaft`](shaft.md), [Mining](../../../features/mining.md), [`ore_vein`](../../mining/ore_vein.md), [`memory`](../../types/memory.md), [Default colony scripts](../../../features/colony-scripts.md) (`colony.miner`).
