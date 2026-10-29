# `lib/mine`

Mining the levels of the shaft [`lib/shaft`](shaft.md) dug. A module of the mod's default library: `import lib.mine`, then `lib.mine.mine_level(20)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/mine.py`, written with `mineshaft_at` and `ore_vein` (see [Mining](../../index.md#mining)). How it digs, what it keeps and what goes into the chest is on [Mining](../../../features/mining.md#libminepy); this page lists the functions.

It works on the shaft in `memory["shaft"]`. How many rounds of a level are done is kept in `memory["mine"]` (level to rounds); how far a round is dug is read from the world, so a call again goes on where it stopped.

## `mine_level(level, rounds=1)`

Digs the hall of the level at height `level` and its next `rounds` rounds of branches, mines every ore vein it uncovers, puts torches at the level's torch spots, and puts the ore and what it does not keep into the landing chest, also whenever its inventory fills. Where water or lava is in or beside the next block, it seals it, stops that hall or branch and goes on with the next.

- **Returns** a dict as [`dig_shaft`](shaft.md#dig_shaftlevels) gives (`ok`, `reason`, `at`, `missing`) and `sealed`, the fluid blocks where a hall or branch stopped.
- **Pauses** yes, while it digs, walks and puts away.

Without a shaft in memory that has such a level, it stops the script with an assertion error: call `lib.shaft.dig_shaft` first.

| Reason | When |
|---|---|
| `missing_ingredients` | no pickaxe (checked first), or nothing to seal a fluid with; `missing` says which |
| `no_space` | the landing chest is full, or putting away frees too little room |
| another reason | an action's (see [`Result`](../../types/result.md)), with `at` |

## `site(r=0)`

The shaft in `memory["shaft"]` with its branches of round `r`, as `mineshaft_at` gives it; `None` without a shaft in memory. Besides `top` and `levels`, `memory["shaft"]` may hold `branches` (per side, default 4) and `length` (of a branch, default 16) for a shaft of another size.

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

[`lib/shaft`](shaft.md), [Mining](../../../features/mining.md), [`ore_vein`](../../index.md#mining), [`memory`](../../types/memory.md), [Default colony scripts](../../../features/colony-scripts.md) (`colony.miner`).
