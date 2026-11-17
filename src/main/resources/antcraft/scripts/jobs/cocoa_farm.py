# A job: grows a cocoa farm and farms it until a player stops it, for brown dye and cookies. Run it as
# /antcraft bot run jobs.cocoa_farm [x y z [cells [radius]]]: the farm's corner (by default where the bot stands, or
# where a stopped run started), the most cells it grows to (by default as many as keep growing round its corner, most()
# most) and how far to look for what it needs (32 by default). A cell is a row of three floor blocks going east: a column
# of two jungle logs on the middle one, a cocoa pod on its west and east side at each height. Each round it breaks the
# ripe pods (age 2), places a bean back on the log, stores the beans in a depot north of the farm and builds a cell once
# it has or can get the 2 jungle logs and 4 beans one takes (docs/cookbook/cocoa-farm.md). The farm's layout, depot,
# growth and rounds are lib.cellfarm's (#826, #833); this module is its cocoa spec. Another job starts a farm with
# run(at); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.cellfarm
import lib.stock
import lib.terrain
from lib.project import Step
from lib.result import result

LOG = "minecraft:jungle_log"
BEANS = "minecraft:cocoa_beans"
COCOA = "minecraft:cocoa"
NAME = "cocoa_farm"
HEIGHT = 2  # the logs of a cell's column
RIPE = 2  # a pod's age once it drops 3 beans
# A pod grows an age on 1 in 5 of its random ticks, each block of a section getting one on 3 of 4096 ticks: some 14000
# ticks from planted to ripe. A cell's harvest takes the bot some 200, so it is busy at about 70 cells till it learns
# its own harvest ticks (lib.cellfarm.most, #844); the cells that keep growing round its corner cap it first.
GROWTH = 2 * 5 * 4096 / 3
HARVEST = 200
KEEP = ["minecraft:crafting_table"]  # lib.get makes a depot chest with it


def column(low):
    """The cell's logs, on the middle of its floor."""
    return [low + (1, y, 0) for y in range(1, HEIGHT + 1)]


def pods(low, high):
    """The cell's pods and the way each faces, toward its log: on the west and east side of each."""
    heights = range(1, HEIGHT + 1)
    return [(low.up(y), "east") for y in heights] + [(high.up(y), "west") for y in heights]


def build_cell(low, high):
    """Clears the blocks over the floor (lib.terrain.flatten), stands the logs in the middle and places a bean on each
    side of them. Gives result(): ok, or the reason of the step that failed with at."""
    r = lib.terrain.flatten(low, high.up(HEIGHT), low.y)
    if not r["ok"]:
        return r
    for p in column(low):
        if block(p).id != LOG:
            r = place(p, LOG, facing="up")
            if not r:
                return result(r.reason, p)
    for p, facing in pods(low, high):
        if block(p).id != COCOA:
            r = place(p, BEANS, facing=facing)
            if not r:
                return result(r.reason, p)
    return result()


def ripe_pods(low, high):
    """The cell's ripe pods, with the way each faces."""
    found = []
    for p, facing in pods(low, high):
        b = block(p)
        if b is not None and b.id == COCOA and b.state["age"] >= RIPE:
            found.append((p, facing))
    return found


def harvest(low, high, told):
    """Breaks the cell's ripe pods, picks up the beans and places one back on each log face; unripe pods stay. Gives
    result(): ok, or the reason of the mine or place that failed with at."""
    ripe = ripe_pods(low, high)
    for p, facing in ripe:
        r = mine(p)
        if not r:
            return result(r.reason, p)
    pick_up(BEANS)
    for p, facing in ripe:
        r = place(p, BEANS, facing=facing)
        if not r:
            return result(r.reason, p)
    return result()


def tend(boxes, told, radius=32):
    """Places a bean on each log face of the cells boxes that lost its pod, with beans from the chests within radius."""
    bare = [(p, f) for b in boxes for p, f in pods(b[0], b[1])
            if block(p).is_air and block(p + {"east": (1, 0, 0), "west": (-1, 0, 0)}[f]).id == LOG]
    if bare and count(BEANS) < len(bare):
        lib.stock.take_up_to(BEANS, len(bare), radius)
    for p, f in bare:
        if count(BEANS):
            place(p, BEANS, facing=f)


def spec(radius=32):
    """The lib.cellfarm spec of the cocoa farm."""
    return {"size": (3, 1), "categories": {"cocoa": [BEANS, LOG]}, "corners": "cocoa_farms", "keep": KEEP,
            "materials": lambda: {LOG: HEIGHT, BEANS: 2 * HEIGHT}, "height": HEIGHT,
            "build_cell": build_cell, "ripe": lambda low, high: len(ripe_pods(low, high)) > 0,
            "harvest": harvest, "tend": lambda boxes, told: tend(boxes, told, radius),
            "most": lambda: lib.cellfarm.most(NAME, GROWTH, HARVEST)}


def run(at=None, size=None, radius=32):
    """Grows and farms the cocoa farm with its corner at at (where the bot stands, or where the last run started), up to
    size cells (else lib.cellfarm.limit). Never returns but on a failed step: gives lib.project.run's result() then."""
    f = spec(radius)
    return lib.cellfarm.run(f, NAME, "cocoa", at, [Step("farm", lib.cellfarm.farm, (f, NAME, size, radius))], radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None,
        int(args[3]) if len(args) > 3 else None, int(args[4]) if len(args) > 4 else 32)
