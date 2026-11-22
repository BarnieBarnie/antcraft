# A job: grows a mushroom farm, for mushroom stew, and farms it until a player stops it. Run it as
# /antcraft bot run jobs.mushroom_farm [x y z [cells [radius]]] [storage x y z]: the farm's corner (by default where the bot stands, or
# where a stopped run started), the most cells it grows to (by default as many as keep growing round its corner, most()
# most) and how far to look for what it needs (32 by default). A cell is a row of three floor blocks of podzol, which a
# mushroom grows on in any light, so the farm needs no roof or dark room: a brown mushroom on the west one and a red one
# on the east one, which spread onto the middle one on their own. Each round it harvests what spread, also onto the path
# round a cell, leaving the two to spread again, fells the huge mushrooms that bone meal from the chests grew on those two
# where there was room (#842), stores it in a depot north of the farm and builds a cell once it has or can get the podzol and the two
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
# cells till it learns its own harvest ticks (lib.cellfarm.most, #844); the cells that keep growing round its
# corner cap it first.
GROWTH = 4096 / 3 * 25 / 2
HARVEST = 200
BONE_MEAL = "minecraft:bone_meal"
STEM = "minecraft:mushroom_stem"
# What a huge mushroom is made of (#842).
HUGE = [STEM, "minecraft:brown_mushroom_block", "minecraft:red_mushroom_block"]
# A huge mushroom grows 4 to 6 over its mushroom (8 to 12 on 1 in 12), its cap at the top: the air room looks for.
TALL = 6
# The scaffold the bot climbs to a cap with, from the chests: as many blocks as the cap is high over its mushroom (harvest).
SCAFFOLD = "#antcraft:scaffold_blocks"
# An axe from the chests fells a huge mushroom faster than the hand (#859); none is made (ensure_tool may start a long
# chain of gets for it).
AXES = "#minecraft:axes"
# The rounds tend skips the chests at most after it found no bone meal there, and its backoff and skip (#859).
BACKOFF = 16
MISSED = {}
# Kept on the bot, not stored: the crafting table lib.get makes a depot chest with, and the bone meal.
KEEP = ["minecraft:crafting_table", BONE_MEAL]


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
    """The cell's mushrooms that spread, to harvest: those on the middle spot and on the path round the cell, where they
    spread in the dark (#842)."""
    out = []
    for x in range(low.x - 1, high.x + 2):
        for z in range(low.z - 1, high.z + 2):
            p = Pos(x, low.y + 1, z)
            if p not in (low.up(), high.up()) and block(p).id in (BROWN, RED):
                out.append(p)
    return out


def huge(low, high):
    """The stems of the huge mushrooms grown from the cell's kept spots."""
    return [p for p, m in kept(low, high) if block(p).id == STEM]


def ripe(low, high):
    """Whether a mushroom spread onto the cell or its path, or a huge mushroom grew on it."""
    return len(spread(low, high)) > 0 or len(huge(low, high)) > 0


def mushroom_blocks(base):
    """The blocks of the huge mushroom whose stem stands at base: those of HUGE reached from it, diagonal steps too, at
    most 256 (a cap is some 50)."""
    seen = {base: True}
    todo = [base]
    out = []
    while todo and len(out) < 256:
        p = todo.pop()
        out.append(p)
        for d in lib.trees.AROUND:
            q = p + d
            if q not in seen:
                seen[q] = True
                b = block(q)
                if b is not None and b.id in HUGE:
                    todo.append(q)
    return out


def harvest(low, high, told, radius=32):
    """Fells each huge mushroom of the cell (lib.trees.fell_whole over its blocks, with scaffold from the chests to
    climb), mines what spread, and picks up the mushrooms, leaving the two kept. Gives result(): ok, or the reason of
    the felling or mine that failed with at. The felling's one stand is in the stem's column, the head just under the
    cap's top, which reaches the whole cap: lib.trees.stands took some 170 ticks to work a cap of 50 blocks out, and its
    stands some 600 to fell it, against 300 from the one. The scaffold is as much as the stand's height takes: a fixed 8
    left the bot short under a tall one's cap, walking there till it gave up (#852). Where the bot and the chests have too
    little, the huge mushroom is left standing and that said once. An axe from the chests where the bot carries none:
    a cap 5 over the floor took some 270 ticks with a stone axe, 470 by hand (#859)."""
    for p in huge(low, high):
        blocks = mushroom_blocks(p)
        top = max([q.y for q in blocks])
        climb = top - p.y
        if count(SCAFFOLD) < climb:
            lib.stock.take_up_to(SCAFFOLD, climb, radius)
        if count(SCAFFOLD) < climb:
            # Left standing: short, the bot climbed what it had and left the cap half mined, its drops lying (#859).
            lib.cellfarm.tell(told, f"harvest: short of scaffold, {climb} for the huge mushroom at {p}")
            continue
        if not count(AXES):
            lib.stock.take_up_to(AXES, 1, radius)
        r = lib.trees.fell_whole(p, False, blocks, [Pos(p.x, top - 2, p.z)])
        if not r["ok"]:
            lib.cellfarm.tell(told, f"harvest: {r['reason']} at {r['at']}")
            return r
    for p in spread(low, high):
        r = mine(p)
        if not r:
            return result(r.reason, p)
    pick_up([BROWN, RED])
    return result()


def room(p, m):
    """Whether a huge mushroom of m has room to grow from p: air up to TALL over it, round the stem as far as its cap
    reaches (3 for a brown one, 2 for a red one). ponytail: one box, not the cap's shape, and only TALL high: vanilla
    checks the room for the height it picks, so the tall kind (1 in 12) can still grow into what is over the box."""
    r = 3 if m == BROWN else 2
    for y in range(p.y + 1, p.y + TALL + 1):
        for x in range(p.x - r, p.x + r + 1):
            for z in range(p.z - r, p.z + r + 1):
                if not block(Pos(x, y, z)).is_air:
                    return False
    return True


def tend(boxes, told, radius=32):
    """Plants a mushroom where a cell of boxes lost one it keeps, with mushrooms from the chests within radius, then
    uses bone meal on each kept one with room for a huge mushroom until it grows, bone meal from those chests (#842)."""
    for b in boxes:
        for p, m in kept(b[0], b[1]):
            if block(p).is_air and lib.trees.is_kind(p.down(), GROWS):
                if not count(m):
                    lib.stock.take_up_to(m, 1, radius)
                if count(m):
                    place(p, m)
    # After a miss the chests are skipped 1, 2, 4 rounds up to BACKOFF (#859), as lib.depot.add_chest does; found ends it.
    # ponytail: in rounds, not ticks, and kept for the run only, not in memory.
    if not count(BONE_MEAL):
        if MISSED.get("skip"):
            MISSED["skip"] -= 1
        elif lib.stock.take_up_to(BONE_MEAL, 64, radius)["ok"] or count(BONE_MEAL):
            MISSED.clear()
        else:
            MISSED["backoff"] = min(BACKOFF, 2 * MISSED.get("backoff", 0) or 1)
            MISSED["skip"] = MISSED["backoff"]
    for b in boxes:
        for p, m in kept(b[0], b[1]):
            if count(BONE_MEAL) and block(p).id == m and room(p, m):
                while count(BONE_MEAL) and block(p).id == m and use(p, BONE_MEAL):
                    pass


def spec(radius=32):
    """The lib.cellfarm spec of the mushroom farm."""
    return {"size": (3, 1), "categories": {"mushrooms": [BROWN, RED, PODZOL]}, "corners": "mushroom_farms",
            "kept": lib.cellfarm.floor,
            "keep": KEEP, "materials": lambda: {PODZOL: 3, BROWN: 1, RED: 1}, "gets": lib.treefarm.get_outside,
            "tools": tools, "build_cell": lambda low, high: build_cell(low, high, radius),
            "ripe": ripe, "harvest": lambda low, high, told: harvest(low, high, told, radius),"tend": lambda boxes, told: tend(boxes, told, radius),
            "most": lambda: lib.cellfarm.most(NAME, GROWTH, HARVEST)}


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
    args = lib.stock.storage_arg(args)  # storage x y z: take from that colony store first (#1019)
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None,
        int(args[3]) if len(args) > 3 else None, int(args[4]) if len(args) > 4 else 32)
