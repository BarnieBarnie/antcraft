# A job: grows a sugarcane farm and farms it until a player stops it, for paper and books. Run it as
# /antcraft bot run jobs.cane_farm [x y z [cells [radius]]]: the farm's corner (by default where the bot stands, or
# where a stopped run started), the most cells it grows to (by default as many as keep growing round its corner, most()
# most) and how far to look for what it needs (32 by default). A cell is a row of three floor blocks: water in the middle
# (poured with jobs.farm.pour), dirt either side with cane on it. Each round it cuts the cane two or more high down to its
# bottom block, which grows again, stores the cane in a depot north of the farm and builds a cell once it has or can get
# the cane one takes, its own harvest (docs/cookbook/cane-farm.md). The farm's layout, depot, growth and rounds are
# lib.cellfarm's (#826, #829); this module is its cane spec. Another job starts a farm with run(at); imported, this runs
# nothing.
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
import lib.trees
from lib.project import Step
from lib.result import result

CANE = "minecraft:sugar_cane"
NAME = "cane_farm"
SOIL = "#minecraft:supports_sugar_cane"  # dirt, grass, mud, moss and sand (#844)
# Cane grows a block on every 16th random tick of its bottom block (age 0 to 15), each tick randomTickSpeed (3) blocks
# of a 16x16x16 section get one: some 22000 ticks to grow back to two high. A cell's harvest takes the bot some 200, so
# it is busy at about 100 cells till it learns
# its own harvest ticks (lib.cellfarm.most, #844); the cells that keep growing round its corner cap it first.
GROWTH = 16 * 4096 / 3
HARVEST = 200
# Kept on the bot, not stored: the buckets that water a cell, and the crafting table lib.get makes a depot chest with.
KEEP = ["minecraft:bucket", "minecraft:water_bucket", "minecraft:crafting_table"]


def sides(low, high):
    """The cell's two floor blocks cane grows on; the water is between them."""
    return [low, high]


def water(low):
    """The cell's water block, in the floor between its sides."""
    return low + (1, 0, 0)


def waters():
    """The water blocks of the farm's built cells, which pour fills no bucket from."""
    return [water(lib.cells.cell(NAME, i)[0]) for i in lib.cells.built(NAME)] if NAME in memory.get("cells", {}) else []


def kept(low, high):
    """The cell's blocks no walk breaks nor puts scaffold in: its floor (lib.cellfarm.floor) and the block over its
    water, where a walk of the harvest left the bot on scaffold over the water (#912)."""
    return lib.cellfarm.floor(low, high) + [water(low).up()]


def tools(low, high):
    """The tools to dig the water's hole and the sides that are no soil (SOIL), such as stone: kind to uses."""
    t = {}
    for p in [water(low)] + [s for s in sides(low, high) if not lib.trees.is_kind(s, SOIL)]:
        for k, uses in lib.terrain.needs(p, p, p.y - 1)["tools"].items():
            t[k] = t.get(k, 0) + uses
    return t


def build_cell(low, high, radius=32):
    """Clears the block over the floor, puts dirt on each side that is no soil (SOIL, so sand stays; lib.treefarm.soil),
    pours the water between them and plants cane on both. Gives result(): ok, or the reason of the step that failed with
    at."""
    r = lib.terrain.flatten(low, high.up(), low.y)
    if not r["ok"]:
        return r
    for s in sides(low, high):
        r = lib.treefarm.soil(s, radius, kind=SOIL)
        if not r["ok"]:
            return r
    if not jobs.farm.pour(water(low), radius, waters()):
        return result("no_water", water(low))
    for s in sides(low, high):
        if block(s.up()).id != CANE:
            r = place(s.up(), CANE)
            if not r:
                return result(r.reason, s.up())
    return result()


def ripe(low, high):
    """Whether cane on a side stands two or more high."""
    return any([block(s.up(2)).id == CANE for s in sides(low, high)])


def harvest(low, high, told):
    """Breaks the cane from the second block up on each side, so the bottom block grows again, and picks up the drops.
    Gives result(): ok, or the reason of the mine that failed with at."""
    for s in sides(low, high):
        p = s.up(2)
        if block(p).id == CANE:
            r = mine(p)
            if not r:
                return result(r.reason, p)
    pick_up(CANE)
    return result()


def tend(boxes, told, radius=32):
    """Pours the water of each cell of boxes that lost it (jobs.farm.pour, its cane popped off with it, #844), then plants
    cane on each side that lost it, with cane from the chests within radius."""
    for b in boxes:
        if block(water(b[0])).id != lib.farm.WATER:
            jobs.farm.pour(water(b[0]), radius, waters())
    bare = [s.up() for b in boxes for s in sides(b[0], b[1]) if block(s.up()).is_air]
    if bare and count(CANE) < len(bare):
        lib.stock.take_up_to(CANE, len(bare), radius)
    for p in bare:
        if count(CANE):
            place(p, CANE)


def spec(radius=32):
    """The lib.cellfarm spec of the cane farm."""
    return {"size": (3, 1), "categories": {"cane": [CANE]}, "corners": "cane_farms",
            "kept": kept, "keep": KEEP,
            "materials": lambda: {CANE: 2}, "gets": lib.treefarm.get_outside,
            "tools": tools, "build_cell": lambda low, high: build_cell(low, high, radius),
            "ripe": ripe, "harvest": harvest, "tend": lambda boxes, told: tend(boxes, told, radius),
            "most": lambda: lib.cellfarm.most(NAME, GROWTH, HARVEST)}


def run(at=None, size=None, radius=32):
    """Grows and farms the cane farm with its corner at at (where the bot stands, or where the last run started), up to
    size cells (else lib.cellfarm.limit). Never returns but on a failed step: gives lib.project.run's result() then."""
    f = spec(radius)
    return lib.cellfarm.run(f, NAME, "cane", at, [Step("farm", lib.cellfarm.farm, (f, NAME, size, radius))], radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None,
        int(args[3]) if len(args) > 3 else None, int(args[4]) if len(args) > 4 else 32)
