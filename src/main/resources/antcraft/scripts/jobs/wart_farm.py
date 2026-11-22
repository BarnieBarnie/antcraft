# A job: grows a nether wart farm, the base of every potion, and farms it until a player stops it. Run it as
# /antcraft bot run jobs.wart_farm [x y z [cells [radius]]] [storage x y z]: the farm's corner (by default where the bot stands, or
# where a stopped run started), the most cells it grows to (by default as many as keep growing round its corner, most()
# most) and how far to look for what it needs (32 by default). A cell is a row of three floor blocks of soul sand with
# wart on each; wart needs no water and grows in any dimension, so the farm can stand in the overworld once a chest holds
# soul sand and wart from the nether (the bot does not go there). Each round it harvests the ripe wart (age 3) and
# replants it, stores the rest in a depot north of the farm and builds a cell once it has or can get the wart and soul
# sand one takes: wart from its own harvest, soul sand from the chests (docs/cookbook/wart-farm.md). The farm's layout,
# depot, growth and rounds are lib.cellfarm's (#826, #834); this module is its wart spec. Another job starts a farm
# with run(at); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.cellfarm
import lib.stock
import lib.terrain
import lib.treefarm
from lib.project import Step
from lib.result import result

WART = "minecraft:nether_wart"
SOUL_SAND = "minecraft:soul_sand"
NAME = "wart_farm"
RIPE = 3  # the age of ripe wart
# Wart ages a stage on 1 in 10 of its random ticks, and each block of a section gets one on 3 of 4096 ticks: some
# 40000 ticks to ripen. A cell's harvest takes the bot some 200, so it is busy at about 200 cells till it learns
# its own harvest ticks (lib.cellfarm.most, #844); the cells that keep growing round its corner cap it first.
GROWTH = RIPE * 10 * 4096 / 3
HARVEST = 200
# Kept on the bot, not stored: the crafting table lib.get makes a depot chest with.
KEEP = ["minecraft:crafting_table"]


def spots(low, high):
    """The cell's floor blocks wart grows on: all three of its row."""
    return [low, low + (1, 0, 0), high]


def tools(low, high):
    """The tools to dig the spots that are no soul sand, such as stone: kind to uses."""
    t = {}
    for p in spots(low, high):
        if block(p).id != SOUL_SAND:
            for k, uses in lib.terrain.needs(p, p, p.y - 1)["tools"].items():
                t[k] = t.get(k, 0) + uses
    return t


def build_cell(low, high):
    """Clears the block over the floor, puts soul sand on each spot that is none (mining what is there) and plants wart
    on them. Gives result(): ok, or the reason of the step that failed with at."""
    r = lib.terrain.flatten(low, high.up(), low.y)
    if not r["ok"]:
        return r
    for s in spots(low, high):
        if block(s).id != SOUL_SAND:
            if not block(s).is_air:
                r = mine(s)
                if not r and r.reason != "target_gone":
                    return result(r.reason, s)
            r = place(s, SOUL_SAND)
            if not r:
                return result(r.reason, s)
        if block(s.up()).is_air:
            r = place(s.up(), WART)
            if not r:
                return result(r.reason, s.up())
    return result()


def is_ripe(p):
    """Whether ripe wart stands at p."""
    b = block(p)
    return b.id == WART and b.state["age"] >= RIPE


def ripe(low, high):
    """Whether wart on a spot is ripe."""
    return any([is_ripe(s.up()) for s in spots(low, high)])


def harvest(low, high, told):
    """Mines each ripe wart, picks up the drops and plants wart again on its spot. Gives result(): ok, or the reason of
    the mine that failed with at."""
    picked = [s.up() for s in spots(low, high) if is_ripe(s.up())]
    for p in picked:
        r = mine(p)
        if not r:
            return result(r.reason, p)
    pick_up(WART)
    for p in picked:
        if count(WART):
            place(p, WART)  # ponytail: one that fails is planted by tend
    return result()


def tend(boxes, told, radius=32):
    """Plants wart on each soul sand spot of the cells boxes that lost it, with wart from the chests within radius."""
    bare = [s.up() for b in boxes for s in spots(b[0], b[1]) if block(s).id == SOUL_SAND and block(s.up()).is_air]
    if bare and count(WART) < len(bare):
        lib.stock.take_up_to(WART, len(bare), radius)
    for p in bare:
        if count(WART):
            place(p, WART)


def spec(radius=32):
    """The lib.cellfarm spec of the wart farm."""
    return {"size": (3, 1), "categories": {"wart": [WART, SOUL_SAND]}, "corners": "wart_farms",
            "kept": lib.cellfarm.floor, "keep": KEEP,
            "materials": lambda: {WART: 3, SOUL_SAND: 3}, "gets": lib.treefarm.get_outside,
            "tools": tools, "build_cell": build_cell,
            "ripe": ripe, "harvest": harvest, "tend": lambda boxes, told: tend(boxes, told, radius),
            "most": lambda: lib.cellfarm.most(NAME, GROWTH, HARVEST)}


def run(at=None, size=None, radius=32):
    """Grows and farms the wart farm with its corner at at (where the bot stands, or where the last run started), up to
    size cells (else lib.cellfarm.limit). Never returns but on a failed step: gives lib.project.run's result() then."""
    f = spec(radius)
    return lib.cellfarm.run(f, NAME, "wart", at, [Step("farm", lib.cellfarm.farm, (f, NAME, size, radius))], radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    args = lib.stock.storage_arg(args)  # storage x y z: take from that colony store first (#1019)
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None,
        int(args[3]) if len(args) > 3 else None, int(args[4]) if len(args) > 4 else 32)
