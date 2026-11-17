# A job: grows a pumpkin or melon farm from a few seeds and farms it until a player stops it. Run it as /antcraft bot
# run jobs.stem_farm <pumpkin|melon> [x y z [cells [radius]]]: the kind, the farm's first corner (by default where the
# bot stands, or where a stopped run of the kind started), the most cells it grows to (by default 24 till it learns its
# harvest ticks, and no more than the cells that get random ticks round its corner) and how far to look for what it
# needs, in blocks (32 by default). A cell is 3 by 3: a stem on farmland at each corner, a dirt fruit spot between each
# two, and water in the middle that keeps the farmland wet. Stems keep fruiting, so the farm only mines the fruit beside
# its stems, never the stems, and stores it in a depot north of the farm; it builds the next cell once it has or can get
# 4 seeds, crafted from a stored pumpkin or melon slices (docs/cookbook/stem-farm.md, #828). The farm's layout, depot,
# growth and rounds are lib.cellfarm's (#826); this module is its spec. Another job starts a farm with run(kind, at);
# imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import jobs.farm
import lib.cellfarm
import lib.cells
import lib.farm
import lib.stock
import lib.terrain
import lib.treefarm
from lib.get import ensure_tool, get
from lib.project import Step
from lib.result import result

# kind: (fruit block, what mining it gives, seed, stem block)
KINDS = {"pumpkin": ("minecraft:pumpkin", "minecraft:pumpkin", "minecraft:pumpkin_seeds", "minecraft:pumpkin_stem"),
         "melon": ("minecraft:melon", "minecraft:melon_slice", "minecraft:melon_seeds", "minecraft:melon_stem")}
SIZE = (3, 3)
# A stem on wet farmland ripens in some 30000 ticks, then fruits about every 5000: with 4 stems a cell has a fruit some
# 1250 ticks after its last harvest. A harvest of a fruit takes some 50 ticks, so a bot keeps up with some 24 cells till
# it learns its own harvest ticks (lib.cellfarm.most, #844); the cells that keep growing round its corner cap it first.
GROWTH = 5000 / 4
HARVEST = 52
KEEP = ["minecraft:bucket", "minecraft:crafting_table"]


def stems(low, high):
    """The floor blocks of the cell from low to high whose stems stand over them: its corners."""
    return [low, Pos(high.x, low.y, low.z), Pos(low.x, low.y, high.z), high]


def spots(low, high):
    """The cell's fruit spots, the dirt between each two stems: the middle of each side."""
    c = low + (1, 0, 1)
    return [c + (0, 0, -1), c + (-1, 0, 0), c + (1, 0, 0), c + (0, 0, 1)]


def centre(low):
    """The cell's water source, in the middle of its floor."""
    return low + (1, 0, 1)


def fruit(kind, low, high):
    """The fruit of kind beside the stems of the cell, in it or on a path round it: a stem fruits onto any dirt beside it."""
    found = []
    for s in stems(low, high):
        for d in [(1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1)]:
            p = s.up() + d
            b = block(p)
            if b is not None and b.id == KINDS[kind][0] and p not in found:
                found.append(p)
    return found


def build_cell(kind, name, low, high, radius):
    """Builds the cell: clears the blocks over its floor and fills its holes with dirt (lib.terrain.flatten), puts dirt
    under each stem and fruit spot and the middle (lib.treefarm.soil), pours water in the middle (jobs.farm.pour, never
    filling a bucket at another cell's), tills the stems' dirt with a hoe and plants the seeds. Gives result()."""
    seed, stem = KINDS[kind][2], KINDS[kind][3]
    ss = stems(low, high)
    r = lib.terrain.flatten(low, Pos(high.x, low.y + 1, high.z), low.y, keep=[s.up() for s in ss])
    if not r["ok"]:
        return r
    for p in spots(low, high) + [centre(low)] + [s for s in ss if block(s).id != lib.farm.FARMLAND]:
        r = lib.treefarm.soil(p, radius)
        if not r["ok"]:
            return r
    wells = [centre(lib.cells.cell(name, i)[0]) for i in lib.cells.built(name)]
    if not jobs.farm.pour(centre(low), radius, wells):
        return result("no_water", centre(low))
    if [s for s in ss if block(s).id in lib.farm.SOIL]:
        r = ensure_tool("hoe", len(ss), None, radius)
        if not r["ok"]:
            return r
    lib.farm.till(ss)
    lib.farm.plant(ss, seed)
    bare = [s for s in ss if block(s.up()).id != stem]
    return result("blocked", bare[0].up()) if bare else result()


def harvest(kind, low, high):
    """Mines the fruit beside the cell's stems; the stems stay and fruit again. Gives result()."""
    for p in fruit(kind, low, high):
        r = mine(p)
        if not r:
            return result(r.reason, p)
    return result()


def tend(kind, boxes, told, radius):
    """Plants a stem again on each stem spot of the cells boxes that lost it, such as one a mob trampled, which turns its
    farmland to dirt (#844): tills the dirt with a hoe and plants a seed of kind from the chests within radius; says
    once where it gets no hoe."""
    seed = KINDS[kind][2]
    bare = [s for b in boxes for s in stems(b[0], b[1]) if block(s.up()).is_air]
    if not bare:
        return
    if [s for s in bare if block(s).id in lib.farm.SOIL]:
        r = ensure_tool("hoe", len(bare), None, radius)
        if not r["ok"]:
            lib.cellfarm.tell(told, f"tend: {r['reason']}")
            return
    if count(seed) < len(bare):
        lib.stock.take_up_to(seed, len(bare), radius)
    lib.farm.till(bare)
    lib.farm.plant(bare, seed)


def gets(item, n, radius):
    """get, but dirt none from under a farm's cells (lib.treefarm.get_outside)."""
    if item == lib.treefarm.DIRT:
        return lib.treefarm.get_outside(item, n, radius)
    return get(item, n, radius)


def spec(kind, name, radius):
    """The lib.cellfarm spec of the stem farm name of kind: ripe where fruit stands beside a stem, harvested by mining
    it; a stem that is gone is planted again (tend)."""
    fruit_item, seed = KINDS[kind][1], KINDS[kind][2]
    return {"size": SIZE, "categories": {"harvest": [fruit_item, seed]}, "corners": "stem_farms", "keep": KEEP,
            "materials": lambda: {seed: 4}, "gets": lambda item, n, r: gets(item, n, r),
            "build_cell": lambda low, high: build_cell(kind, name, low, high, radius),
            "ripe": lambda low, high: len(fruit(kind, low, high)) > 0,
            "harvest": lambda low, high, told: harvest(kind, low, high),
            "tend": lambda boxes, told: tend(kind, boxes, told, radius),
            "most": lambda: lib.cellfarm.most(name, GROWTH, HARVEST)}


def run(kind, at=None, size=None, radius=32):
    """Grows and farms a stem farm of kind ("pumpkin" or "melon") with its first corner at at, up to size cells (else
    lib.cellfarm.limit). Never returns but on a failed step: gives lib.project.run's result() then."""
    name = kind + "_farm"
    f = spec(kind, name, radius)
    return lib.cellfarm.run(f, name, kind, at, [Step("farm", lib.cellfarm.farm, (f, name, size, radius))], radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(args[0], Pos(int(args[1]), int(args[2]), int(args[3])) if len(args) > 3 else None,
        int(args[4]) if len(args) > 4 else None, int(args[5]) if len(args) > 5 else 32)
