# `lib/build`

Building a blueprint end to end: get what its site lacks, build it, give back what was borrowed. A module of the mod's default library: `from lib.build import build_here, materials` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/build.py`, a kit over [`lib/get`](get.md), [`lib/borrow`](borrow.md) and [`lib/result`](result.md) (see [The script library](../../../design/library.md#layers)).

```python
from lib.build import build_here

r = build_here("antcraft:cobble_step", Pos(10, 64, 10))
if not r["ok"]:
    say(f"not built: {r['reason']} {r['missing']}")
```

## `materials(id, at, turn=0, mirror=False)`

What the blueprint `id`, built with its lowest corner at `at`, turned `turn` quarters clockwise and mirrored where `mirror`, still lacks there: a dict of item id to count, empty once it stands. `None` where there is no blueprint `id`. It is [`blueprint`](../../building/blueprint.md)`(id, at, turn, mirror)["missing"]`, so a half-built site lacks only the rest. Done at once.

## `build_here(id, at, turn=0, mirror=False, radius=32)`

Gets each item of `materials(id, at, turn, mirror)` with [`get`](get.md#getitem-n1-radius32) within `radius`, in the blueprint's order, then [`build`](../../building/build.md)s what it has. Where a `get` fails, it still gets the rest and builds as far as it can (`build` goes on to the first block it lacks), then fails with that `get`'s reason. Where the build stops short with `missing_ingredients` (it used some dirt to pillar with, a block was taken meanwhile), it goes round again while each round leaves less missing. At the end, also on a failure, it gives back what it borrowed during the call ([`give_back`](borrow.md#give_backradius16-since0)): a tool goes back to its chest, and on a failure so do the materials it took.

- **Returns** a [`result()`](result.md) dict: `ok` once the blueprint stands. Else `at` is `at`, `reason` is `no_blueprint`, that of the build (such as `protected`), the reason of the first `get` that failed (such as `no_logs` or `no_recipe`), or `no_progress` where a round left as much missing as the one before; `missing` is what the bot lacks to finish, after what it borrowed went back.
- **Pauses** yes, while it gets and builds.

The colony builder ([`colony.builder`](../../../features/colony-scripts.md)) takes from the Colony Core first and posts haul jobs for what `get` fails on, so it calls `materials` and builds itself rather than `build_here`.

## See also

[`blueprint`](../../building/blueprint.md), [`build`](../../building/build.md), [`lib/get`](get.md), [`lib/borrow`](borrow.md), [Build a blueprint](../../../cookbook/build.md), [The script library](../../../design/library.md).
