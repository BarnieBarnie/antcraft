# `lib/shaft`

Digging a ladder mineshaft and climbing it. A module of the mod's default library: `import lib.shaft`, then `lib.shaft.dig_shaft([40, 20])` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/shaft.py`, written with [`mineshaft`](../../mining/mineshaft.md) and `mineshaft_at`. What the shaft looks like and how digging it goes is on [Mining](../../../features/mining.md#libshaftpy); this page lists the functions.

The shaft is kept in `memory["shaft"]` (its `top` and `levels`); how far it is dug is read from the world, so a call after a restart, a reload or a failure goes on where it stopped.

## `dig_shaft(levels)`

Digs the shaft down to the heights `levels` (a list of y values, such as `[40, 20]`), top down: the column with a ladder in each block, a wooden trapdoor in the hatch, and at each level a chamber with a torch and a chest. It walls off water, lava and air and digs out what falls in, and ends in the chamber of the deepest level.

- **Returns** a dict: `ok` (`True` when done), `reason` (`None`, or why it stopped), `at` (the block it stopped at, or `None`) and `missing` (a dict of item to how many it lacks).
- **Pauses** yes, while it digs, walks and places.

It checks what it carries before it digs at all: ladders, a trapdoor, torches and chests for what is left to do, and a pickaxe. Making what it lacks is the caller's work.

| Reason | When |
|---|---|
| `no_site` | [`mineshaft`](../../mining/mineshaft.md) finds no place for the shaft beside the bot |
| `missing_ingredients` | the bot lacks what `missing` lists, or has nothing left to wall off with |
| `blocked` | something, such as a mob, kept standing where a block goes |
| another reason | an action's (see [`Result`](../../types/result.md)), with `at` |

## `to_level(level)`

Climbs to the landing of the level at height `level` of the shaft in memory. Gives [`goto`](../../movement/goto.md)'s [`Result`](../../types/result.md) and pauses while it climbs. Without a shaft in memory that has such a level, it stops the script with an assertion error: call `dig_shaft` first.

## `to_surface()`

Climbs out of the shaft in memory onto the ground beside its top. Gives `goto`'s `Result` and pauses while it climbs; without a shaft in memory, it stops the script with an assertion error.

## `shaft(levels=None)`

The shaft as [`mineshaft`](../../mining/mineshaft.md) gives it: the one in `memory["shaft"]`, else a new site for `levels`, which is kept there; `None` without either. Forget the kept one with `memory.pop("shaft")` to dig another. It may pause while it searches; a reflex that takes over meanwhile only pauses the search, so `shaft` still gives the site once the reflex lets go.

## Example

Dig a shaft, mine from its lowest level, and come back up:

```python
import lib.shaft

r = lib.shaft.dig_shaft([40, 20])
if r["ok"]:
    lib.shaft.to_surface()
elif r["reason"] == "missing_ingredients":
    say(f"I need {r['missing']}")
else:
    warn(f"shaft stopped: {r['reason']} at {r['at']}")
```

## See also

[`lib/mine`](mine.md), [Mining](../../../features/mining.md), [`mineshaft`](../../mining/mineshaft.md), [`mineshaft_at`](../../mining/mineshaft_at.md), [`memory`](../../types/memory.md), [Default colony scripts](../../../features/colony-scripts.md) (`colony.miner`).
