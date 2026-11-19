# A job: fishes until a player stops it, for food (fish) and loot. Run it as /antcraft bot run jobs.fish [x y z [radius]]:
# the water block to cast at (by default the open water fish() finds within 16 blocks) and how far to look for chests
# (32 by default). Each round it eats when hungry (lib.food.upkeep, which also takes fish back from the chests), fishes
# BATCH items and puts them in the nearest chests with room (lib.stock.chests). When its rod breaks it gets another
# (lib.get.get: from a chest, or crafted from string and sticks), and warns and stops where it cannot (#962;
# docs/cookbook/fish.md). Another job fishes with run(at, radius); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.food
import lib.get
import lib.stock

ROD = "minecraft:fishing_rod"
BATCH = 1  # items caught between trips to the chest, which stands by the water


def carried():
    """Item id to how many the bot carries."""
    counts = {}
    for s in inventory():
        counts[s.item] = counts.get(s.item, 0) + s.count
    return counts


def store(before, radius=32):
    """Puts what the bot carries more of than before (item id to how many) in the chests within radius, nearest first.
    Gives the item ids no chest had room for."""
    chests = lib.stock.chests(radius)
    full = []
    now = carried()
    for item in now:
        k = now[item] - before.get(item, 0)
        for chest in chests:
            if k > 0 and room(chest, item):
                r = put(chest, item, k)
                k -= r.value if r else 0
        if k > 0:
            full.append(item)
    return full


def run(at=None, radius=32):
    """Fishes at at (else the open water fish() finds) forever, storing the catch in the chests within radius. Gives
    the result of the fish or get that stopped it."""
    while True:
        lib.food.upkeep(radius)
        if not count(ROD):
            r = lib.get.get(ROD, 1, radius)
            if not r["ok"]:
                warn("fish: no fishing rod, and none to get:", r["reason"])
                return r
        before = carried()
        r = fish(BATCH, at)
        full = store(before, radius)
        if full:
            warn("fish: no chest has room for", full)
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
