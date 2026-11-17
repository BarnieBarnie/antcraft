# A job: grows a sweet berry farm, food that needs no replanting and no water, and farms it until a player stops it. Run
# it as /antcraft bot run jobs.berry_farm [x y z [cells [radius]]]: the farm's corner (by default where the bot stands,
# or where a stopped run started), the most cells it grows to (by default as many as keep growing round its corner, MOST
# at most) and how far to look for what it needs (32 by default). A cell is a row of three floor blocks of dirt with a
# sweet berry bush on each. Each round it picks the ripe bushes (age 2 or 3) with use, which leaves each bush standing
# at age 1, never breaking one, picks up the berries, stores them in a depot north of the farm and builds a cell once it
# has or can get the berries one takes, its own harvest (docs/cookbook/berry-farm.md). A bush hurts what moves through
# it, so paths never enter one (LevelWorldView.isHazard). The farm's layout, depot, growth and rounds are lib.cellfarm's
# (#826, #832); this module is its berry spec. Another job starts a farm with run(at); imported, this runs nothing.
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

BERRIES = "minecraft:sweet_berries"
BUSH = "minecraft:sweet_berry_bush"
NAME = "berry_farm"
RIPE = 2  # the age from which a bush gives berries
# A bush ages a stage on 1 in 5 of its random ticks, and each block of a section gets one on 3 of 4096 ticks: some 7000
# ticks from picked (age 1) to ripe. A cell's harvest takes the bot some 200, so it is busy at about 35 cells;
# ponytail: not measured, the cells that keep growing round its corner cap it first.
MOST = int((RIPE - 1) * 5 * 4096 / 3 // 200)
# Kept on the bot, not stored: the crafting table lib.get makes a depot chest with.
KEEP = ["minecraft:crafting_table"]


def spots(low, high):
    """The cell's floor blocks a bush grows on: all three of its row."""
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
    """Clears the block over the floor, puts dirt on each spot that is no soil (lib.treefarm.soil) and plants a bush on
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
            r = place(s.up(), BERRIES)
            if not r:
                return result(r.reason, s.up())
    return result()


def is_ripe(p):
    """Whether a bush with berries stands at p."""
    b = block(p)
    return b.id == BUSH and b.state["age"] >= RIPE


def ripe(low, high):
    """Whether a bush on a spot has berries."""
    return any([is_ripe(s.up()) for s in spots(low, high)])


def harvest(low, high, told):
    """Picks each ripe bush with use, which leaves it standing at age 1, and picks up the berries. Gives result(): ok, or
    the reason of the use that failed with at."""
    for s in spots(low, high):
        p = s.up()
        if is_ripe(p):
            r = use(p)
            if not r:
                return result(r.reason, p)
    pick_up(BERRIES)
    return result()


def tend(boxes, told, radius=32):
    """Plants a bush on each spot of the cells boxes that lost it, with berries from the chests within radius."""
    bare = [s.up() for b in boxes for s in spots(b[0], b[1]) if block(s.up()).is_air]
    if bare and count(BERRIES) < len(bare):
        lib.stock.take_up_to(BERRIES, len(bare), radius)
    for p in bare:
        if count(BERRIES):
            place(p, BERRIES)


def spec(radius=32):
    """The lib.cellfarm spec of the berry farm."""
    return {"size": (3, 1), "categories": {"berries": [BERRIES]}, "corners": "berry_farms", "keep": KEEP,
            "materials": lambda: {BERRIES: 3}, "gets": lib.treefarm.get_outside,
            "tools": tools, "build_cell": lambda low, high: build_cell(low, high, radius),
            "ripe": ripe, "harvest": harvest, "tend": lambda boxes, told: tend(boxes, told, radius),
            "most": lambda: MOST}


def run(at=None, size=None, radius=32):
    """Grows and farms the berry farm with its corner at at (where the bot stands, or where the last run started), up
    to size cells (else lib.cellfarm.limit). Never returns but on a failed step: gives lib.project.run's result() then."""
    f = spec(radius)
    return lib.cellfarm.run(f, NAME, "berries", at, [Step("farm", lib.cellfarm.farm, (f, NAME, size, radius))], radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None,
        int(args[3]) if len(args) > 3 else None, int(args[4]) if len(args) > 4 else 32)
