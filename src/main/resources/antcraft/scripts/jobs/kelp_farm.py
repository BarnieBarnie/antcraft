# A job: farms kelp in a pool of water until a player stops it, for dried kelp (food) and dried kelp blocks (fuel). Run
# it as /antcraft bot run jobs.kelp_farm [x1 y1 z1 x2 y2 z2 [radius [dry [food]]]]: the corners of the pool's floor, the
# blocks kelp stands on (by default those of the last run), how far to look for kelp to plant (32 by default), dry to
# smelt the kelp to dried kelp and craft each 9 to a dried kelp block (#841), and food, how many dried kelp to keep as
# food, uncrafted (0 by default; #858). The pool is one
# already there, two deep or more: the bot works it from its rim where the reach of place and mine (4 from the eyes) gets
# there, else swimming at the surface (#840; docs/cookbook/kelp-farm.md). Each round it plants kelp on each floor block
# under water that lacks it, cuts the kelp two high or more down to its bottom block, so it grows again, and stores the
# kelp mined (or, with dry, the dried kelp and its blocks) in a depot north of the pool. Another job farms a pool with run(low, high); imported, this runs nothing.
#
# Run as /antcraft bot run jobs.kelp_farm cells [x y z [cells [radius [dry [food [depth]]]]]] it grows a farm of water
# cells instead (#857), its kelp water-cell spec on lib.cellfarm (cell_spec, grow): each cell a column of the ground dug
# depth deep (2 by default), water poured in layer by layer (jobs.farm.pour) and kelp planted on its floor, harvested
# from the surface; the corner, cells and radius as jobs.cane_farm's, dry and food as the pool's (#877).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import jobs.farm
import lib.borrow
import lib.cellfarm
import lib.cells
import lib.craft
import lib.depot
import lib.food
import lib.stock
import lib.terrain
import lib.treefarm
from lib.project import Step
from lib.result import result

KELP = "minecraft:kelp"
STALK = ["minecraft:kelp", "minecraft:kelp_plant"]  # the top block and the blocks under it
WATER = "minecraft:water"
DRIED = "minecraft:dried_kelp"
BLOCK = "minecraft:dried_kelp_block"
# The fuel dry takes for a smelt, best first, with how many kelp one item smelts: a dried kelp block the farm made first.
FUELS = [(BLOCK, 20), ("minecraft:coal", 8), ("minecraft:charcoal", 8)]
NAME = "kelp_farm"
WAIT = 100  # ticks between rounds that cut nothing
CELLS = "kelp_cells"  # the water-cell farm's name (grow), its cells and depot apart from the pool's
# Kelp's top block grows on 14% of its random ticks, each tick randomTickSpeed (3) blocks of a 16x16x16 section get
# one: some 9800 ticks to grow a block. A cell's harvest, a mine from the surface, takes the bot some 100.
GROWTH = 4096 / 3 / 0.14
HARVEST = 100
# Kept on the bot, not stored: the buckets that water a cell, and the crafting table lib.get makes a depot chest with.
KEEP = ["minecraft:bucket", "minecraft:water_bucket", "minecraft:crafting_table"]
# The faces of a cell's top block a bucket is aimed at to pour it over the kelp: its look meets the ground beside it (#877).
SIDES = ["north", "south", "east", "west"]


def spots(low, high):
    """The floor blocks of the pool kelp stands on."""
    return [Pos(x, low.y, z) for x in range(min(low.x, high.x), max(low.x, high.x) + 1)
            for z in range(min(low.z, high.z), max(low.z, high.z) + 1)]


def plant(low, high, radius=32):
    """Plants kelp on each spot under water, from what the bot carries or the chests within radius hold. Gives how many
    it planted."""
    bare = [s.up() for s in spots(low, high) if block(s.up()).id == WATER]
    if bare and count(KELP) < len(bare):
        lib.stock.take_up_to(KELP, len(bare), radius)
    n = 0
    for p in bare:
        if count(KELP) and place(p, KELP):
            n += 1
    return n


def surface(low):
    """The height of the pool's rim: the first block over the floor at low that holds neither water nor kelp."""
    y = low.y + 1
    while block(Pos(low.x, y, low.z)).id in [WATER] + STALK:
        y += 1
    return y


def harvest(low, high):
    """Mines each kelp from its top down to its spot's second block, so the bottom block stays and grows again; the kelp
    goes straight into the inventory, none left to float up. Gives how many stalks it cut."""
    top = surface(low)
    n = 0
    for s in spots(low, high):
        cut = False
        for y in range(top - 1, s.y + 1, -1):
            p = Pos(s.x, y, s.z)
            if block(p).id in STALK and mine(p):
                cut = True
        n += 1 if cut else 0
    return n


def dry(radius=32, food=0, name=NAME, spare=0):
    """Smelts the kelp the bot carries and the depot holds, a stack at most, to dried kelp in a furnace within radius
    (else one lib.craft.smelt_here puts down), burning a dried kelp block, else coal or charcoal, carried or taken from
    the chests within radius (else the best fuel carried), and puts what it took and did not burn back (#858); then
    crafts each 9 dried kelp the bot carries and the depot holds, but food of them kept as food (#858), to a dried kelp
    block, at a crafting table within radius (else lib.craft.craft_here's). Kelp in other chests, the planting stock, is
    left alone. The depot is name's; spare kelp are left raw, as the water cells' planting stock (#877). Gives how many
    kelp it smelted."""
    since = len(lib.borrow.borrowed())
    lib.stock.take_up_to(KELP, lib.craft.STACK, chests=lib.depot.chests(name))
    n = max(0, min(count(KELP) - spare, lib.craft.STACK))
    before = count(DRIED)
    if n:
        fuel = None
        for f, k in FUELS:
            lib.stock.take_up_to(f, -(-n // k), radius)
            if count(f):
                fuel = f
                break
        furnace = find_nearest("minecraft:furnace", radius=radius)
        if furnace is None:
            lib.craft.smelt_here(KELP, n, fuel=fuel)
        else:
            smelt(KELP, n, furnace=furnace, fuel=fuel)
    # The fuel left over, and any kelp over a stack, back to their chests.
    lib.borrow.give_back(since=since)
    smelted = count(DRIED) - before
    lib.stock.take_up_to(DRIED, lib.craft.STACK, chests=lib.depot.chests(name))
    k = max(0, count(DRIED) - food) // 9
    if k:
        table = find_nearest("minecraft:crafting_table", radius=radius)
        if table is None:
            lib.craft.craft_here(BLOCK, k)
        else:
            craft(BLOCK, k, table=table)
    return smelted


# The water-cell farm (#857). A cell is one column of the ground, low its top block: dug out depth deep (layers), so
# the kelp stands on the block depth under low, low.down(depth - 1) its bottom block, low its top, grown up to the
# surface. Kelp grows only into a water source, so each layer over the bottom one holds a source (#877).
# ponytail: the depth is not kept with the farm; run it again with another and its built cells are farmed as that deep.


def layers(low, depth=2):
    """The cell at low's blocks, its top first."""
    return [low.down(i) for i in range(depth)]


def waters(low=None, depth=2):
    """The water blocks of the cell farm's built cells, and of the cell at low, which pour fills no bucket from."""
    built = [lib.cells.cell(CELLS, i)[0] for i in lib.cells.built(CELLS)] if CELLS in memory.get("cells", {}) else []
    return [p for c in built + ([low] if low else []) for p in layers(c, depth)]


def cell_tools(low, high, depth=2):
    """The tools to dig the cell's layers: kind to uses."""
    return lib.terrain.needs(low.down(depth - 1), low, low.y - depth)["tools"]


def plant_cell(low, radius=32, depth=2):
    """Plants kelp in the cell at low's bottom block where it lacks it, a water source poured there first where it is
    none (jobs.farm.pour), with kelp the bot carries or takes from the chests within radius; then pours a source in each
    layer over it, from the bottom up, where it lost it, aimed at a side of the hole (SIDES), not into the kelp (#877).
    Gives result(): ok, else no_water or the reason the place failed, with at."""
    cell = layers(low, depth)
    bottom = cell[-1]
    if block(bottom).id not in STALK:
        if not jobs.farm.pour(bottom, radius, waters(low, depth)):
            return result("no_water", bottom)
        if not count(KELP):
            lib.stock.take_up_to(KELP, 1, radius)
        r = place(bottom, KELP)
        if not r:
            return result(r.reason, bottom)
    # ponytail: every side is taken as ground, as the cell is dug in it.
    for p in reversed(cell[:-1]):
        if block(p).id not in STALK and not jobs.farm.pour(p, radius, waters(low, depth), SIDES):
            return result("no_water", p)
    return result()


def build_water_cell(low, high, radius=32, depth=2):
    """Clears the block over the cell, then digs each layer from the top down and pours a source in it, the water over
    it falling in first, and plants kelp in the bottom one (plant_cell). In that order: a bucket's look goes through
    water and over kelp would pour into the kelp, where the water is lost, so each layer is poured before the kelp is
    there. Gives result()."""
    # ponytail: the floor under the cell is taken as solid, as the ground it is dug in; a cave there fails the place.
    cell = layers(low, depth)
    for p in [low.up()] + cell:
        if not block(p).is_air and block(p).id not in [WATER] + STALK:
            r = mine(p)
            if not r:
                return result(r.reason, p)
        if p in cell[:-1] and block(p).id not in STALK and not jobs.farm.pour(p, radius, waters(low, depth)):
            return result("no_water", p)
    return plant_cell(low, radius, depth)


def harvest_cell(low, high, told, depth=2):
    """Cuts the kelp grown up to the cell's top block from the top down, its bottom block left to grow again: a kelp a
    layer over it (#877). Gives result()."""
    if block(low).id in STALK:
        for p in layers(low, depth)[:-1]:
            if block(p).id in STALK:
                r = mine(p)
                if not r:
                    return result(r.reason, p)
    return result()


def dry_cells(radius, food):
    """The water-cell farm's chores with dried: dry over its depot, a kelp left raw to plant the next cell with; no
    harvest of its own (#877)."""
    dry(radius, food, CELLS, 1)
    return 0, False


def cell_spec(radius=32, dried=False, food=0, depth=2):
    """The lib.cellfarm spec of the kelp water-cell farm, its cells depth deep; with dried, its kelp dried each round
    (dry_cells)."""
    f = {"size": (1, 1), "categories": {"kelp": [KELP], "dried": [DRIED, BLOCK]}, "corners": "kelp_farms", "keep": KEEP,
            "materials": lambda: {KELP: 1}, "gets": lib.treefarm.get_outside,
            "tools": lambda low, high: cell_tools(low, high, depth),
            "build_cell": lambda low, high: build_water_cell(low, high, radius, depth),
            "ripe": lambda low, high: block(low).id in STALK,
            "harvest": lambda low, high, told: harvest_cell(low, high, told, depth),
            "tend": lambda boxes, told: [plant_cell(b[0], radius, depth) for b in boxes],
            "most": lambda: lib.cellfarm.most(CELLS, GROWTH, HARVEST)}
    if dried:
        f["chores"] = lambda keep, told: dry_cells(radius, food)
    return f


def grow(at=None, size=None, radius=32, dried=False, food=0, depth=2):
    """Grows and farms the kelp water-cell farm with its corner at at (where the bot stands, or where the last run
    started), up to size cells (else lib.cellfarm.limit), each depth deep, a cut giving depth - 1 kelp; with dried, its
    kelp smelted and pressed as run's (dry), food dried kelp left uncrafted. Never returns but on a failed step: gives
    lib.project.run's result() then."""
    f = cell_spec(radius, dried, food, depth)
    return lib.cellfarm.run(f, CELLS, "kelp", at, [Step("farm", lib.cellfarm.farm, (f, CELLS, size, radius))], radius)


def run(low=None, high=None, radius=32, dried=False, food=0):
    """Farms the pool whose floor runs from low to high (else the last run's) forever, storing the kelp in a depot north
    of it, at the surface; with dried, smelted and crafted first (dry), food dried kelp left uncrafted."""
    box = memory.get(NAME) if low is None else (Pos(low), Pos(high))
    if box is None:
        say("kelp_farm: give the corners of the pool's floor")
        return
    memory[NAME] = box
    low, high = box
    # At the rim, over the water, two north of the pool.
    lib.depot.depot(NAME, Pos(min(low.x, high.x), surface(low), min(low.z, high.z) - 2), "east", {"kelp": [KELP], "dried": [DRIED, BLOCK]})
    told = False
    while True:
        lib.food.upkeep(radius)
        planted = plant(low, high, radius)
        if planted and not told:
            say(f"planted {planted} kelp")
            told = True
        cut = harvest(low, high)
        if cut:
            say(f"cut {cut} kelp")
        if dried:
            dry(radius, food)
        lib.depot.store(NAME, None, radius)
        if not cut:
            wait(WAIT)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    if args and args[0] == "cells":
        grow(Pos(int(args[1]), int(args[2]), int(args[3])) if len(args) > 3 else None,
             int(args[4]) if len(args) > 4 else None, int(args[5]) if len(args) > 5 else 32,
             len(args) > 6 and args[6] == "dry", int(args[7]) if len(args) > 7 else 0, int(args[8]) if len(args) > 8 else 2)
    elif len(args) >= 6:
        run(Pos(int(args[0]), int(args[1]), int(args[2])), Pos(int(args[3]), int(args[4]), int(args[5])),
            int(args[6]) if len(args) > 6 else 32, len(args) > 7 and args[7] == "dry", int(args[8]) if len(args) > 8 else 0)
    else:
        run()
