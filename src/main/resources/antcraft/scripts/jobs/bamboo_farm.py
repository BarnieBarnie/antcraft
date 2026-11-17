# A job: grows a bamboo farm and farms it until a player stops it, for scaffolding, sticks and furnace fuel. Run it as
# /antcraft bot run jobs.bamboo_farm [x y z [cells [radius]]]: the farm's corner (by default where the bot stands, or
# where a stopped run started), the most cells it grows to (by default as many as keep growing round its corner, MOST at
# most) and how far to look for what it needs (32 by default). A cell is a row of three floor blocks of dirt with bamboo
# on each; bamboo needs no water. Each round it cuts the bamboo two or more high down to its bottom block, which grows
# again, sweeps up the drops, stores them in a depot north of the farm and builds a cell once it has or can get the
# bamboo one takes, its own harvest (docs/cookbook/bamboo-farm.md). The farm's layout, depot, growth and rounds are
# lib.cellfarm's (#826, #831); this module is its bamboo spec. Another job starts a farm with run(at); imported, this
# runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.cellfarm
import lib.stock
import lib.terrain
import lib.treefarm
import lib.trees
from lib.project import Step
from lib.result import result

BAMBOO = "minecraft:bamboo"
NAME = "bamboo_farm"
# Bamboo grows a block on about every third random tick of its top, each tick randomTickSpeed (3) blocks of a 16x16x16
# section get one: some 4000 ticks a block. A cell's harvest takes the bot some 200, so it is busy at about 20 cells;
# ponytail: not measured, the cells that keep growing round its corner cap it first.
MOST = int(3 * 4096 / 3 // 200)
# Kept on the bot, not stored: the crafting table lib.get makes a depot chest with.
KEEP = ["minecraft:crafting_table"]
# How far the drops of a cut stalk, up to 16 high, scatter: pick_up sweeps this far round the bot.
SWEEP = 12


def spots(low, high):
    """The cell's floor blocks bamboo grows on: all three of its row."""
    return [low, low + (1, 0, 0), high]


def tools(low, high):
    """The tools to dig the spots that are no soil, such as stone: kind to uses."""
    t = {}
    for p in spots(low, high):
        if not lib.trees.is_kind(p, lib.treefarm.SOIL):
            for k, uses in lib.terrain.needs(p, p, p.y - 1)["tools"].items():
                t[k] = t.get(k, 0) + uses
    return t


def build_cell(low, high, radius=32):
    """Clears the block over the floor, puts dirt on each spot that is no soil (lib.treefarm.soil) and plants bamboo on
    them. Gives result(): ok, or the reason of the step that failed with at."""
    r = lib.terrain.flatten(low, high.up(), low.y)
    if not r["ok"]:
        return r
    for s in spots(low, high):
        r = lib.treefarm.soil(s, radius)
        if not r["ok"]:
            return r
    for s in spots(low, high):
        if block(s.up()).is_air:
            r = place(s.up(), BAMBOO)
            if not r:
                return result(r.reason, s.up())
    return result()


def ripe(low, high):
    """Whether bamboo on a spot stands two or more high."""
    return any([block(s.up(2)).id == BAMBOO for s in spots(low, high)])


def harvest(low, high, told):
    """Breaks each stalk at its second block, so all above it drops and the bottom block grows again, and sweeps up the
    drops. Gives result(): ok, or the reason of the mine that failed with at."""
    for s in spots(low, high):
        p = s.up(2)
        if block(p).id == BAMBOO:
            r = mine(p)
            if not r:
                return result(r.reason, p)
    pick_up(BAMBOO, SWEEP)
    return result()


def tend(boxes, told, radius=32):
    """Plants bamboo on each spot of the cells boxes that lost it, with bamboo from the chests within radius."""
    bare = [s.up() for b in boxes for s in spots(b[0], b[1]) if block(s.up()).is_air]
    if bare and count(BAMBOO) < len(bare):
        lib.stock.take_up_to(BAMBOO, len(bare), radius)
    for p in bare:
        if count(BAMBOO):
            place(p, BAMBOO)


def spec(radius=32):
    """The lib.cellfarm spec of the bamboo farm."""
    return {"size": (3, 1), "categories": {"bamboo": [BAMBOO]}, "corners": "bamboo_farms", "keep": KEEP,
            "materials": lambda: {BAMBOO: 3}, "gets": lib.treefarm.get_outside,
            "tools": tools, "build_cell": lambda low, high: build_cell(low, high, radius),
            "ripe": ripe, "harvest": harvest, "tend": lambda boxes, told: tend(boxes, told, radius),
            "most": lambda: MOST}


def run(at=None, size=None, radius=32):
    """Grows and farms the bamboo farm with its corner at at (where the bot stands, or where the last run started), up
    to size cells (else lib.cellfarm.limit). Never returns but on a failed step: gives lib.project.run's result() then."""
    f = spec(radius)
    return lib.cellfarm.run(f, NAME, "bamboo", at, [Step("farm", lib.cellfarm.farm, (f, NAME, size, radius))], radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None,
        int(args[3]) if len(args) > 3 else None, int(args[4]) if len(args) > 4 else 32)
