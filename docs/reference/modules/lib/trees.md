# `lib/trees`

Finding and felling trees. A module of the mod's default library: `import lib.trees`, then `lib.trees.fell(lib.trees.next_tree())` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/trees.py`, written with [`scan`](../../world/scan.md), [`block`](../../world/block.md), [`mine`](../../blocks/mine.md), [`pick_up`](../../inventory/pick_up.md) and [`place`](../../blocks/place.md). The colony lumberjack (`colony.lumberjack`) is built on it.

## `next_tree(radius=32, where=None)`

The base, the lowest log, of the nearest tree within `radius` blocks, or `None`. `where` is a function a base must pass, such as `in_colony` from `colony.common`; `None` takes any. It pauses only while its [`scan`](../../world/scan.md) does.

```python
import lib.trees

def near_home(p):
    return p.distance(home()) < 16

tree = lib.trees.next_tree(where=near_home)
```

## `is_tree(base)`

Whether the trunk with its lowest log at `base` is a tree: it stands on dirt and has leaves at its top, so a log wall or a log cabin is not. Done at once.

## `fell(base, replant=True)`

Mines the trunk at `base` from the ground up, picks up the logs and saplings that fell and, with `replant`, plants a sapling the bot carries where the trunk stood. It stops at a log it cannot mine; whether `base` is still a log says so.

- **Returns** `None`.
- **Pauses** yes, while it walks, mines and picks up.

## `logs(n, radius=32)`

Mines logs within `radius` blocks until the bot carries `n` more, the lowest logs first and the nearest among those, so it takes trunk bottoms it reaches from the ground and never climbs a tall tree. A log it cannot mine is skipped.

- **Returns** a dict: `ok`, `reason` (`None`, `no_logs` once no log it can mine is left, or `no_space` with a full inventory), `at` (where `no_space` struck) and `missing` (`{"#minecraft:logs": k}`, the logs it still lacks, with `no_logs`).
- **Pauses** yes, while it walks, mines and picks up.

```python
import lib.trees

r = lib.trees.logs(3)
if not r["ok"]:
    say("no logs: " + r["reason"])
```

## `low_log(radius=32, skip=None)`

The lowest log within `radius`, the nearest among those, leaving out the positions in the dict `skip`; `None` if there is none.

## `is_log(p)`, `trunk_base(log)`

Whether the block at `p` is a log, and the lowest log of the trunk `log` is in. Done at once.

## `LOGS`, `SAPLINGS`

The selectors `"#minecraft:logs"` and `"#minecraft:saplings"`.

## Example

Fell every tree within 16 blocks, then store the logs:

```python
import lib.trees

tree = lib.trees.next_tree(radius=16)
while tree is not None:
    lib.trees.fell(tree)
    if lib.trees.is_log(tree):
        break  # could not fell it
    tree = lib.trees.next_tree(radius=16)
put(memory["chest"], lib.trees.LOGS)
```

## See also

[`scan`](../../world/scan.md), [`mine`](../../blocks/mine.md), [Default colony scripts](../../../features/colony-scripts.md).
