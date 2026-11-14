# A grid of farm cells that grows one cell at a time, for any script: `import lib.cells`, then
# lib.cells.layout("wood", origin, (5, 5)), lib.cells.cell("wood", lib.cells.next("wood")) and lib.cells.add("wood", i).
# A module of the mod's default library (lib/), a primitive: it imports nothing (docs/design/library.md, Primitives).
# A farm is kept in memory["cells"][name], so it survives restarts, reloads, respawns and a stop and rerun.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.


def farm(name):
    f = memory.get("cells", {}).get(name)
    if f is None:
        raise ValueError("no farm " + name + ": lay it out first")
    return f


def layout(name, origin, size, row=4, gap=1):
    """Sets up the farm name: cells of size (w, d) blocks, row cells to a row along x, rows along z, gap blocks of path
    between cells, cell 0 with its low corner at origin. A farm already laid out keeps its layout and the cells built.
    Gives the farm, a dict with origin, size, row, gap and built."""
    cells = memory.get("cells", {})
    if name not in cells:
        cells[name] = {"origin": Pos(origin), "size": [size[0], size[1]], "row": row, "gap": gap, "built": []}
        memory["cells"] = cells
    return dict(cells[name])


def cell(name, i):
    """The (low, high) box of cell i of the farm name, one block high at the origin's y: row-major from the origin."""
    f = farm(name)
    w, d = f["size"]
    gap = f["gap"]
    low = f["origin"] + ((i % f["row"]) * (w + gap), 0, (i // f["row"]) * (d + gap))
    return (low, low + (w - 1, 0, d - 1))


def built(name):
    """The indices of the cells of the farm name built, lowest first."""
    return sorted(farm(name)["built"])


def add(name, i):
    """Marks cell i of the farm name built; a cell built already stays built once."""
    f = farm(name)
    if i not in f["built"]:
        f["built"].append(i)

def next(name):
    """The lowest index of a cell of the farm name not built."""
    b = farm(name)["built"]
    i = 0
    while i in b:
        i += 1
    return i


def count(name):
    """How many cells of the farm name are built."""
    return len(farm(name)["built"])
