# `lib/mine`

Mining the levels of the shaft [`lib/shaft`](shaft.md) dug. A module of the mod's default library: `import lib.mine`, then `lib.mine.mine_level(20)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/mine.py`, written with [`mineshaft_at`](../../mining/mineshaft_at.md) and [`ore_vein`](../../mining/ore_vein.md). How it digs, what it keeps and what goes into the chest is on [Mining](../../../features/mining.md#libminepy); this page lists the functions.

It works on the shaft in `memory["shaft"]`. How many rounds of a level are done is kept in `memory["mine"]` (level to rounds); how far a round is dug is read from the world, so a call again goes on where it stopped.

## `mine_level(level, rounds=1)`

Digs the hall of the level at height `level` and its next `rounds` rounds of branches, mines every ore vein it uncovers that its pickaxe drops ([`lib.tools.drops`](tools.md)), puts torches at the level's torch spots, and puts the ore into the landing chest, also whenever its inventory fills. The stone and other junk it does not keep it drops where it stands rather than store it, so the chest never fills with junk and the level can be mined round after round; dropped items vanish after 5 minutes, as a player's do. Where water or lava is in or beside the next block, it seals it, stops that hall or branch and goes on with the next.

- **Returns** a dict as [`dig_shaft`](shaft.md#dig_shaftlevels) gives (`ok`, `reason`, `at`, `missing`) and `sealed`, the fluid blocks where a hall or branch stopped.
- **Pauses** yes, while it digs, walks and puts away.

Without a shaft in memory that has such a level, it stops the script with an assertion error: call `lib.shaft.dig_shaft` first.

| Reason | When |
|---|---|
| `missing_ingredients` | no pickaxe (checked first), or nothing to seal a fluid with; `missing` says which |
| `no_space` | the landing chest is full of ore, or putting away frees too little room |
| another reason | an action's (see [`Result`](../../types/result.md)), with `at` |

## `veins(cells, opened, stand, chest=None, left=None, out=None, anywhere=False)`

Mines every ore vein ([`ore_vein`](../../mining/ore_vein.md)) in the walls and ceiling of `cells`, standing at `stand`, and walls off what each opens up; `opened` are the blocks that stay open. `mine_level` and [`lib.dig.branch`](dig.md#branchlength-every8-facingnorth-leftnone-outnone) mine the veins of their tunnels with it. A vein its pickaxe does not drop ([`lib.tools.drops`](tools.md)) stays in the wall, and the tier that drops it is added to the list `left` where given. The blocks of a vein [`lib.find.WHERE`](find.md#where) leaves out stay too, so a vein across the edge of a colony is mined only up to it; each is added to the list `out` where given (#561). With `anywhere`, as `mine_level` passes, it mines the veins whole wherever they reach: the shaft is the site its caller chose, its halls and branches go where the shaft's layout puts them, and a deep level lies outside a colony's `in_colony`, which measures from the core in 3D. With a `chest`, it empties the bot into it when its inventory is nearly full.

- **Returns** `None` once done, else a result dict with the reason it stopped and `at`.
- **Pauses** yes, while it digs.

## `site(r=0)`

The shaft in `memory["shaft"]` with its branches of round `r`, as `mineshaft_at` gives it; `None` without a shaft in memory. Besides `top` and `levels`, `memory["shaft"]` may hold `branches` (per side, default 4) and `length` (of a branch, default 16) for a shaft of another size.

## `tier_at(y)`

The pickaxe tier the ores common at height `y` need: the best [`lib.tools.tier_for`](tools.md) of the ores of `SPREAD` (each ore block with the heights it is common at, from the game's ore spread) at `y`, `"wooden"` where none is. `"stone"` at 16, where iron is the best of them; `"iron"` below -8, where gold, redstone and diamonds are. A modded ore is not in `SPREAD`; add it with its heights in your script and `tier_for` gives its tier from its `needs_*_tool` tags. Else `colony.miner` takes a tier per level from `colony_memory["mineshaft"]["tiers"]` where this is not right.

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
