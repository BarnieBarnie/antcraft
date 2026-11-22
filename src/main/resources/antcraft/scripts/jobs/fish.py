# A job: fishes until a player stops it, for food (fish) and loot. Run it as /antcraft bot run jobs.fish [x y z [radius]]:
# the water block to cast at (by default the open water fish() finds within 16 blocks, else the nearest open water
# within radius, which it walks to and keeps in memory["fish"], #994) and how far to look for chests and water
# (32 by default). Each round it eats when hungry (lib.food.upkeep, which also takes fish back from the chests), fishes
# BATCH items and sorts them into depot "fish" (lib.depot), a chest per kind: fish, enchanted books, rods and the rest,
# its row of chests starting by the spot and growing as a new kind is caught (#995). When its rod breaks it gets another
# (lib.get.get: from a chest, or crafted from string and sticks), and warns and stops where it cannot (#962;
# docs/cookbook/fish.md). Another job fishes with run(at, radius); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.depot
import lib.find
import lib.food
import lib.get
from lib.dig import FACING, turn

ROD = "minecraft:fishing_rod"
BATCH = 1  # items caught between trips to the chest, which stands by the water
NAME = "fish"  # the depot, memory["depots"]["fish"]
CATEGORIES = {"fish": ["#minecraft:fishes"], "books": ["minecraft:enchanted_book"], "rods": [ROD]}
# The rod it fishes with (one: a rod caught goes to the rods chest) and the crafting table lib.get makes a chest with.
KEEP = [(ROD, 1), "minecraft:crafting_table"]


def depot(spot, radius=32):
    """Makes depot NAME the first time: its row starts one block behind the bot, away from the water at spot (else the
    nearest open water), and runs along the shore."""
    if NAME not in memory.get("depots", {}):
        w = spot if spot is not None else water(radius)
        here = pos()
        d = (here.x - w.x, here.z - w.z) if w is not None else (-1, 0)
        away = ("east" if d[0] > 0 else "west") if abs(d[0]) >= abs(d[1]) else ("south" if d[1] > 0 else "north")
        lib.depot.depot(NAME, here + FACING[away], turn(away, []))
    return lib.depot.depot(NAME, None, None, CATEGORIES)


def water(radius=32):
    """The nearest open water, a water source with air over it, within radius; None if there is none."""
    return lib.find.nearest("minecraft:water", radius, lambda p: block(p).state["level"] == 0 and lib.find.is_air(p.up()))


def run(at=None, radius=32):
    """Fishes at at (else the open water fish() finds) forever, sorting the catch into depot NAME. Gives
    the result of the fish or get that stopped it."""
    spot = at if at is not None else memory.get("fish")
    while True:
        lib.food.upkeep(radius)
        if not count(ROD):
            r = lib.get.get(ROD, 1, radius)
            if not r["ok"]:
                warn("fish: no fishing rod, and none to get:", r["reason"])
                return r
        r = fish(BATCH, spot)
        if not r and r.reason == "no_water" and at is None:  # none within fish()'s 16 blocks, or the spot is gone
            spot = water(radius)
            memory["fish"] = spot
            if spot is not None:
                r = fish(BATCH, spot)
        depot(spot, radius)
        s = lib.depot.store(NAME, KEEP, radius)
        if not s["ok"]:
            warn("fish: no chest has room for", sorted(s["missing"].keys()), "and none to get:", s["reason"])
            return r
        if not r and r.reason == "no_water":
            warn("fish: no water")
            return r
        if not r and r.reason != "rod_broke":
            warn("fish:", r.reason)
            return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None, int(args[3]) if len(args) > 3 else 32)
