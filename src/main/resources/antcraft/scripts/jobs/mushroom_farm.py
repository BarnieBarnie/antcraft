# A job: grows a mushroom farm, for mushroom stew, and farms it until a player stops it. Run it as
# /antcraft bot run jobs.mushroom_farm [x y z [cells [radius]]]: the farm's corner (by default where the bot stands, or
# where a stopped run started), the most cells it grows to (by default as many as keep growing round its corner, MOST at
# most) and how far to look for what it needs (32 by default). A cell is a row of three floor blocks of podzol, which a
# mushroom grows on in any light, so the farm needs no roof or dark room: a brown mushroom on the west one and a red one
# on the east one, which spread onto the middle one on their own. Each round it harvests what spread, leaving the two to
# spread again, stores it in a depot north of the farm and builds a cell once it has or can get the podzol and the two
# mushrooms one takes: the mushrooms from its own harvest, the podzol from the chests (docs/cookbook/mushroom-farm.md).
# The farm's layout, depot, growth and rounds are lib.cellfarm's (#826, #836); this module is its mushroom spec.
# Another job starts a farm with run(at); imported, this runs nothing.
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

BROWN = "minecraft:brown_mushroom"
RED = "minecraft:red_mushroom"
PODZOL = "minecraft:podzol"
# The floors a mushroom grows on in any light: podzol, mycelium and nylium.
GROWS = "#minecraft:mushroom_grow_block"
NAME = "mushroom_farm"
# A mushroom tries to spread on 1 in 25 of its random ticks, and each block of a section gets one on 3 of 4096 ticks:
# some 34000 ticks a try for each of a cell's two. A cell's harvest takes the bot some 200, so it is busy at about 85
# cells; ponytail: not measured, the cells that keep growing round its corner cap it first.
MOST = int(4096 / 3 * 25 / 2 // 200)
# Kept on the bot, not stored: the crafting table lib.get makes a depot chest with.
KEEP = ["minecraft:crafting_table"]


def spots(low, high):
    """The cell's floor blocks: all three of its row."""
    return [low, low + (1, 0, 0), high]


def kept(low, high):
    """The cell's mushrooms left to spread, (spot, mushroom) pairs: brown on the west spot, red on the east."""
    return [(low.up(), BROWN), (high.up(), RED)]


def tools(low, high):
    """The tools to dig the spots that are no podzol or mycelium, such as stone: kind to uses."""
    t = {}
    for p in spots(low, high):
        if not lib.trees.is_kind(p, GROWS):
            for k, uses in lib.terrain.needs(p, p, p.y - 1)["tools"].items():
                t[k] = t.get(k, 0) + uses
    return t


def build_cell(low, high, radius=32):
    """Clears the block over the floor, puts podzol on each spot that is none (lib.treefarm.soil) and plants a brown
    and a red mushroom. Gives result(): ok, or the reason of the step that failed with at."""
    r = lib.terrain.flatten(low, high.up(), low.y)
    if not r["ok"]:
        return r
    for s in spots(low, high):
        r = lib.treefarm.soil(s, radius, PODZOL, GROWS)
        if not r["ok"]:
            return r
    for p, m in kept(low, high):
        if block(p).is_air:
            r = place(p, m)
            if not r:
                return result(r.reason, p)
    return result()


def spread(low, high):
    """The cell's mushrooms that spread, to harvest: those on the middle spot."""
    p = low.up() + (1, 0, 0)
    return [p] if block(p).id in (BROWN, RED) else []


def ripe(low, high):
    """Whether a mushroom spread onto the cell."""
    return len(spread(low, high)) > 0


def harvest(low, high, told):
    """Mines what spread and picks it up, leaving the two it spread from. Gives result(): ok, or the reason of the mine
    that failed with at."""
    for p in spread(low, high):
        r = mine(p)
        if not r:
            return result(r.reason, p)
    pick_up(BROWN)
    pick_up(RED)
    return result()


def tend(boxes, told, radius=32):
    """Plants a mushroom where a cell of boxes lost one it keeps, with mushrooms from the chests within radius."""
    for b in boxes:
        for p, m in kept(b[0], b[1]):
            if block(p).is_air and lib.trees.is_kind(p.down(), GROWS):
                if not count(m):
                    lib.stock.take_up_to(m, 1, radius)
                if count(m):
                    place(p, m)


def spec(radius=32):
    """The lib.cellfarm spec of the mushroom farm."""
    return {"size": (3, 1), "categories": {"mushrooms": [BROWN, RED, PODZOL]}, "corners": "mushroom_farms",
            "keep": KEEP, "materials": lambda: {PODZOL: 3, BROWN: 1, RED: 1}, "gets": lib.treefarm.get_outside,
            "tools": tools, "build_cell": lambda low, high: build_cell(low, high, radius),
            "ripe": ripe, "harvest": harvest, "tend": lambda boxes, told: tend(boxes, told, radius),
            "most": lambda: MOST}


def run(at=None, size=None, radius=32):
    """Grows and farms the mushroom farm with its corner at at (where the bot stands, or where the last run started),
    up to size cells (else lib.cellfarm.limit). Never returns but on a failed step: gives lib.project.run's result()
    then."""
    f = spec(radius)
    return lib.cellfarm.run(f, NAME, "mushroom", at, [Step("farm", lib.cellfarm.farm, (f, NAME, size, radius))],
                            radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None,
        int(args[3]) if len(args) > 3 else None, int(args[4]) if len(args) > 4 else 32)
