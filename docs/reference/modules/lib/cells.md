# `lib/cells`

A grid of farm cells that grows one cell at a time: the bookkeeping of a self-expanding farm (trees, a cobblestone generator), so each farm does not write its own. A module of the mod's default library: `import lib.cells`, then `lib.cells.layout("wood", origin, (5, 5))` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/cells.py`, a primitive that imports nothing (see [The script library](../../../design/library.md#layers)). Its boxes go to [`lib/area`](area.md).

A farm is kept in [`memory`](../../types/memory.md)`["cells"][name]`, so the cells built survive a reload, a death or a stop and rerun. Cells are numbered row-major from the origin: cell 0 has its low corner at the origin, the next `row - 1` cells follow along x, then the next row starts one cell further along z. Between cells are `gap` blocks of path. Every function but `layout` stops the script with an error for a farm not laid out.

```python
import lib.cells
import lib.area

lib.cells.layout("wood", Pos(100, 64, 200), (5, 5), row=3)
i = lib.cells.next("wood")
low, high = lib.cells.cell("wood", i)
r = lib.area.clear(low + (0, 1, 0), high + (0, 6, 0))
if r["ok"]:
    lib.cells.add("wood", i)
```

## `layout(name, origin, size, row=4, gap=1)`

Sets up the farm `name`: cells of `size`, `(w, d)` blocks along x and z, `row` cells to a row, `gap` blocks between cells, cell 0 at `origin` (a [`Pos`](../../types/pos.md) or tuple). A farm already laid out keeps its layout and the cells built, so a script can call `layout` each time it starts.

- **Returns** the farm, a dict with `origin`, `size`, `row`, `gap` and `built`.
- **Pauses** no.

## `cell(name, i)`

The box of cell `i` of the farm `name`, as `(low, high)`: one block high, at the origin's y.

- **Pauses** no.

## `built(name)`

The indices of the cells of the farm `name` built, lowest first. Done at once.

## `add(name, i)`

Marks cell `i` of the farm `name` built; adding a cell twice keeps it once. Done at once.

## `next(name)`

The lowest index of a cell of the farm `name` not built: where the farm grows next. Done at once.

## `count(name)`

How many cells of the farm `name` are built. Done at once.

## See also

[`lib/area`](area.md), [`lib/project`](project.md), [`memory`](../../types/memory.md), [The script library](../../../design/library.md).
