# A job: grows a cactus farm and farms it until a player stops it, for green dye and furnace fuel. Run it as
# /antcraft bot run jobs.cactus_farm [x y z [cells [radius]]]: the farm's corner (by default where the bot stands, or
# where a stopped run started), the most cells it grows to (by default as many as keep growing round its corner, most()
# most) and how far to look for what it needs (32 by default). A cell is 2 by 2 floor blocks with cactus on sand at two
# opposite corners, a checkerboard, as a cactus breaks with a block beside it. Each round it cuts the cactus two or more
# high down to its bottom block, which grows again, stores the cactus in a depot north of the farm and builds a cell once
# it has or can get the cactus one takes, its own harvest (docs/cookbook/cactus-farm.md). The farm's layout, depot,
# growth and rounds are lib.cellfarm's (#826, #830); this module is its cactus spec. Paths keep off the blocks beside a
# cactus (PathFinder.PRICKLY_COST), which hurts the bot. Another job starts a farm with run(at); imported, this runs
# nothing.
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

CACTUS = "minecraft:cactus"
SAND = "minecraft:sand"
NAME = "cactus_farm"
# Cactus grows a block on every 16th random tick of its top block, as cane does: some 22000 ticks to grow back to two
# high. A cell's harvest takes the bot some 200, so it is busy at about 100 cells till it learns
# its own harvest ticks (lib.cellfarm.most, #844); the cells that keep growing round its corner cap it first.
GROWTH = 16 * 4096 / 3
HARVEST = 200
# Kept on the bot, not stored: the crafting table lib.get makes a depot chest with.
KEEP = ["minecraft:crafting_table"]


def spots(low, high):
    """The cell's two floor blocks a cactus grows on, opposite corners, so neither has a block beside it."""
    return [low, high]


def tools(low, high):
    """The tools to dig the spots that are no sand, such as stone: kind to uses."""
    t = {}
    for p in [s for s in spots(low, high) if not lib.trees.is_kind(s, "#minecraft:sand")]:
        for k, uses in lib.terrain.needs(p, p, p.y - 1)["tools"].items():
            t[k] = t.get(k, 0) + uses
    return t


def build_cell(low, high, radius=32):
    """Clears the blocks over the floor, puts sand at each spot that is none (lib.treefarm.soil) and plants cactus on
    both. Gives result(): ok, or the reason of the step that failed with at."""
    r = lib.terrain.flatten(low, high.up(), low.y)
    if not r["ok"]:
        return r
    for s in spots(low, high):
        r = lib.treefarm.soil(s, radius, SAND, "#minecraft:sand")
        if not r["ok"]:
            return r
    for s in spots(low, high):
        if block(s.up()).id != CACTUS:
            r = place(s.up(), CACTUS)
            if not r:
                return result(r.reason, s.up())
    return result()


def ripe(low, high):
    """Whether a cactus on a spot stands two or more high."""
    return any([block(s.up(2)).id == CACTUS for s in spots(low, high)])


def harvest(low, high, told):
    """Breaks each cactus from its top down to its second block, so the bottom block grows again: top down, as a block
    over a broken one would break and drop onto the cactus below, which destroys it. mine puts the drops in the
    inventory; pick_up takes any that fell beside. Gives result(): ok, or the reason of the mine that failed with at."""
    for s in spots(low, high):
        top = s.up()
        while block(top.up()).id == CACTUS:
            top = top.up()
        while top.y > s.y + 1:
            r = mine(top)
            if not r and r.reason != "target_gone":
                return result(r.reason, top)
            top = top.down()
    pick_up(CACTUS)
    return result()


def tend(boxes, told, radius=32):
    """Plants cactus on each spot of the cells boxes that lost it, with cactus from the chests within radius."""
    bare = [s.up() for b in boxes for s in spots(b[0], b[1]) if block(s.up()).is_air]
    if bare and count(CACTUS) < len(bare):
        lib.stock.take_up_to(CACTUS, len(bare), radius)
    for p in bare:
        if count(CACTUS):
            place(p, CACTUS)


def spec(radius=32):
    """The lib.cellfarm spec of the cactus farm."""
    return {"size": (2, 2), "categories": {"cactus": [CACTUS]}, "corners": "cactus_farms",
            "kept": lib.cellfarm.floor, "keep": KEEP, "hurts": True,
            "materials": lambda: {CACTUS: 2}, "gets": lib.treefarm.get_outside,
            "tools": tools, "build_cell": lambda low, high: build_cell(low, high, radius),
            "ripe": ripe, "harvest": harvest, "tend": lambda boxes, told: tend(boxes, told, radius),
            "most": lambda: lib.cellfarm.most(NAME, GROWTH, HARVEST)}


def run(at=None, size=None, radius=32):
    """Grows and farms the cactus farm with its corner at at (where the bot stands, or where the last run started), up
    to size cells (else lib.cellfarm.limit). Never returns but on a failed step: gives lib.project.run's result() then."""
    f = spec(radius)
    return lib.cellfarm.run(f, NAME, "cactus", at, [Step("farm", lib.cellfarm.farm, (f, NAME, size, radius))], radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None,
        int(args[3]) if len(args) > 3 else None, int(args[4]) if len(args) > 4 else 32)
