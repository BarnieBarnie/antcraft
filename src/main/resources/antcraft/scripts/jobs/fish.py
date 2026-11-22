# A job: fishes until a player stops it, for food (fish) and loot. Run it as /antcraft bot run jobs.fish [x y z [radius]] [storage x y z]:
# the water block to cast at (by default the open water fish() finds within 16 blocks, else the nearest open water
# within radius, which it walks to and keeps in memory["fish"], #994) and how far to look for chests and water
# (32 by default, and at most 32, #998). Each round it eats when hungry (lib.food.upkeep, which also takes fish back from the chests), fishes
# BATCH items and sorts them into depot "fish" (lib.depot), a chest per kind: fish, enchanted books, rods and the rest,
# its row of chests starting by the spot and growing as a new kind is caught (#995). It fishes with the best rod
# (lib.enchant.best_rod) it carries or the chests within radius hold, taking only that rod from a chest (#999), switching
# to a better one it catches; when its rod broke it looks again, and with none it gets one (lib.get.get: from a chest,
# or crafted from string and sticks), and warns and stops where it cannot (#962, #996; docs/cookbook/fish.md). Another job fishes with run(at, radius); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.depot
import lib.enchant
import lib.find
import lib.food
import lib.get
import lib.stock
from lib.result import result
from lib.dig import FACING, turn

ROD = "minecraft:fishing_rod"
BATCH = 1  # items caught between trips to the chest, which stands by the water
NAME = "fish"  # the depot, memory["depots"]["fish"]
CATEGORIES = {"fish": ["#minecraft:fishes"], "books": ["minecraft:enchanted_book"], "rods": [ROD]}
# The rod it fishes with, held (one: store puts the hand's last, so a worse rod caught goes to the rods chest) and the crafting table lib.get makes a chest with.
KEEP = [(ROD, 1), "minecraft:crafting_table"]
MAX_RADIUS = 32  # the largest radius scan and find_nearest take (BlockScan.MAX_RADIUS)


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


def open_water(p):
    """Whether p is open water: a loaded water source with air over it."""
    b = block(p)
    return b is not None and b.id == "minecraft:water" and b.state["level"] == 0 and lib.find.is_air(p.up())


def around(p):
    """How many of p's eight neighbours are open water."""
    return len([1 for dx in [-1, 0, 1] for dz in [-1, 0, 1] if (dx or dz) and open_water(Pos(p.x + dx, p.y, p.z + dz))])


def water(radius=32):
    """Open water within radius to cast at, as fish() picks it (Fishing.spot): from the nearest, it steps to the
    neighbour with the most open water around it while that has more, so off a pond's edge (#998); None if there is none.
    ponytail: a climb, so a pond whose open middle lies past a narrower neck can keep a lesser spot."""
    w = lib.find.nearest("minecraft:water", min(radius, MAX_RADIUS), open_water)
    while w is not None and around(w) < 8:
        best = w
        for dx in [-1, 0, 1]:
            for dz in [-1, 0, 1]:
                n = Pos(w.x + dx, w.y, w.z + dz)
                if open_water(n) and around(n) > around(best):
                    best = n
        if best == w:
            break
        w = best
    return w


def hold_best():
    """Holds the best fishing rod the bot carries (lib.enchant.best_rod); gives its Stack, None where it carries none."""
    best = lib.enchant.best_rod(inventory())
    if best is not None:
        equip(best)
    return best


def rod(radius=32):
    """Holds the best fishing rod the bot carries or a chest within radius holds: from a chest whose best rod beats the
    bot's it takes only that rod (take of its Stack, #999), so worse ones stay where they are. With none it gets one
    with lib.get.get. Gives result(): ok, or the reason get failed with."""
    for chest in lib.stock.where(ROD, radius):
        best = lib.enchant.best_rod(inventory())
        there = lib.enchant.best_rod(container(chest) or [])
        if there is not None and (best is None or lib.enchant.better(there, best, lib.enchant.ROD)):
            take(chest, there)
    if not count(ROD):
        r = lib.get.get(ROD, 1, radius)
        if not r["ok"]:
            return r
    hold_best()
    return result()


def run(at=None, radius=32):
    """Fishes at at (else the open water fish() finds) forever, sorting the catch into depot NAME. Gives
    the result of the fish or get that stopped it."""
    radius = min(radius, MAX_RADIUS)  # a larger radius would stop the scans of chests and water with an error (#998)
    spot = at if at is not None else memory.get("fish")
    look = True  # whether to look for the best rod in the chests: in the first round, and when its rod broke
    while True:
        lib.food.upkeep(radius)
        if look:
            r = rod(radius)
            if not r["ok"]:
                warn("fish: no fishing rod, and none to get:", r["reason"])
                return r
        r = fish(BATCH, spot)
        if not r and r.reason == "no_water" and at is None:  # none within fish()'s 16 blocks, or the spot is gone
            spot = water(radius)
            memory["fish"] = spot
            if spot is not None:
                r = fish(BATCH, spot)
        h = held()
        look = h is None or h.item != ROD  # its rod broke: fish holds the next one carried only for its next cast (#999)
        depot(spot, radius)
        hold_best()  # a better rod caught is held, so store keeps it and stores the worse
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
    args = lib.stock.storage_arg(args)  # storage x y z: take from that colony store first (#1019)
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None, int(args[3]) if len(args) > 3 else 32)
