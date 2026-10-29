# `blueprint`

```text
blueprint(id, at=None, turn=0, mirror=False)
```

A blueprint's size, role, materials and spots, as plain data.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `id` | str | | the blueprint's id, such as `"antcraft:warehouse"` |
| `at` | [`Pos`](../types/pos.md) or tuple | `None` | where a copy's lowest corner goes, as for [`build`](build.md); `None` gives the spots relative to the blueprint's origin |
| `turn` | int | `0` | quarters turned clockwise, as for `build` |
| `mirror` | bool | `False` | mirrored left to right, as for `build` |

## Returns

A dict, or `None` for an id the server has no blueprint for:

- `size`: a tuple `(x, y, z)`, unturned; `(0, 0, 0)` when the server lacks the blueprint's structure.
- `role`: `"storage"`, `"farm"`, `"generator"` or `None`.
- `materials`: a dict of item id to how many of it `build` places, most first.
- `chests`: a list of [`Pos`](../types/pos.md), the blueprint's chest spots.
- `work_spots`: a list of `Pos`, the spots a bot stands on or works at, such as farm plots.

Without `at`, `chests` and `work_spots` are relative to the blueprint's origin, unturned, with no dimension. Given `at` (and `turn` and `mirror`), they are where a copy built there has them, in the dimension of `at`.

## Pauses

No, it is done at once.

## Cost

100 instructions besides the call.

## Description

A hauler can fetch the materials before the builder starts, and a farmer can find its plots in a farm it built. The dict is plain data, so it can be kept in [`memory`](../types/memory.md). The [Blueprints](../../features/blueprints.md) page lists the bundled blueprints and how a data pack adds more.

## Examples

```python
info = blueprint("antcraft:warehouse")
for item, n in info["materials"].items():
    if count(item) < n:
        print("need", n - count(item), item)
```

```python
at = colony()["core"] + (4, -1, 0)
farm = blueprint("antcraft:farm", at, turn=1)
memory["plots"] = farm["work_spots"]
```

## See also

[`build`](build.md), [Blueprints](../../features/blueprints.md), [`memory`](../types/memory.md).
