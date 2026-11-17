# A job: farms kelp in a pool of water until a player stops it, for dried kelp (food) and dried kelp blocks (fuel). Run
# it as /antcraft bot run jobs.kelp_farm [x1 y1 z1 x2 y2 z2 [radius [dry]]]: the corners of the pool's floor, the blocks
# kelp stands on (by default those of the last run), how far to look for kelp to plant (32 by default), and dry to smelt
# the kelp to dried kelp and craft each 9 to a dried kelp block (#841). The pool is one
# already there, two deep or more: the bot works it from its rim where the reach of place and mine (4 from the eyes) gets
# there, else swimming at the surface (#840; docs/cookbook/kelp-farm.md). Each round it plants kelp on each floor block
# under water that lacks it, cuts the kelp two high or more down to its bottom block, so it grows again, and stores the
# kelp mined (or, with dry, the dried kelp and its blocks) in a depot north of the pool. Another job farms a pool with run(low, high); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.craft
import lib.depot
import lib.food
import lib.stock

KELP = "minecraft:kelp"
STALK = ["minecraft:kelp", "minecraft:kelp_plant"]  # the top block and the blocks under it
WATER = "minecraft:water"
DRIED = "minecraft:dried_kelp"
BLOCK = "minecraft:dried_kelp_block"
# The fuel dry takes for a smelt, best first, with how many kelp one item smelts: a dried kelp block the farm made first.
FUELS = [(BLOCK, 20), ("minecraft:coal", 8), ("minecraft:charcoal", 8)]
NAME = "kelp_farm"
WAIT = 100  # ticks between rounds that cut nothing


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


def dry(radius=32):
    """Smelts the kelp the bot carries and the depot holds, a stack at most, to dried kelp in a furnace within radius
    (else one lib.craft.smelt_here puts down), burning a dried kelp block, else coal or charcoal, carried or taken from
    the chests within radius (else the best fuel carried); then crafts each 9 dried kelp the bot carries and the depot
    holds to a dried kelp block, at a crafting table within radius (else lib.craft.craft_here's). Kelp in other chests,
    the planting stock, is left alone. Gives how many kelp it smelted."""
    lib.stock.take_up_to(KELP, lib.craft.STACK, chests=lib.depot.chests(NAME))
    n = min(count(KELP), lib.craft.STACK)
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
    smelted = count(DRIED) - before
    lib.stock.take_up_to(DRIED, lib.craft.STACK, chests=lib.depot.chests(NAME))
    k = count(DRIED) // 9
    if k:
        table = find_nearest("minecraft:crafting_table", radius=radius)
        if table is None:
            lib.craft.craft_here(BLOCK, k)
        else:
            craft(BLOCK, k, table=table)
    return smelted


def run(low=None, high=None, radius=32, dried=False):
    """Farms the pool whose floor runs from low to high (else the last run's) forever, storing the kelp in a depot north
    of it, at the surface; with dried, smelted and crafted first (dry)."""
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
            dry(radius)
        lib.depot.store(NAME, None, radius)
        if not cut:
            wait(WAIT)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    if len(args) >= 6:
        run(Pos(int(args[0]), int(args[1]), int(args[2])), Pos(int(args[3]), int(args[4]), int(args[5])),
            int(args[6]) if len(args) > 6 else 32, len(args) > 7 and args[7] == "dry")
    else:
        run()
