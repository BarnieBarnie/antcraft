# A sorted storage row that grows, for any script: `import lib.depot`, then lib.depot.depot("wood", origin, "east",
# {"logs": ["#minecraft:logs"]}) once and lib.depot.store("wood") after each trip (docs/reference/modules/lib/depot.md).
# A module of the mod's default library (lib/), a kit: it imports the resolver (lib.get, for a new chest), lib.dig and
# lib.storage below it. A depot is kept in memory["depots"][name], so it survives restarts, reloads and respawns.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.get
import lib.storage
from lib.dig import FACING
from lib.result import result

CHEST = "minecraft:chest"
# The category of what no other category selects.
OTHER = "other"


def depot(name, origin=None, facing="east", categories=None):
    """The depot name: memory["depots"][name], made the first time with its row of chests starting at origin and going
    towards facing ("north", "south", "east" or "west"), and categories, a dict of category name to a list of item ids
    or selectors. Given categories replace those of a depot made before; its chests are kept."""
    depots = memory.setdefault("depots", {})
    if name not in depots:
        depots[name] = {"origin": Pos(origin), "facing": facing, "categories": {}, "chests": []}
    if categories is not None:
        depots[name]["categories"] = categories
    return depots[name]


def chests(name, category=None):
    """The chests of depot name in row order, those of category only where it is given."""
    return [c[1] for c in depot(name)["chests"] if category is None or c[0] == category]


def category(name, item):
    """The category of depot name that selects item, OTHER where none does."""
    for cat, selectors in depot(name)["categories"].items():
        if matches(item, selectors):
            return cat
    return OTHER


def store(name, keep=None, radius=32):
    """Puts everything the bot carries but what keep (a list of item ids or selectors) selects into the chests of depot
    name, and of what no category selects (OTHER) also not lib.storage.KEEP_KINDS or chests, so an apples category takes
    apples though they are food: each item into the chests of its category with room, in row order; where they
    are full, a new chest at the next slot of the row (add_chest) for the category. Gives result(): ok, or the reason
    adding a chest failed with and missing, item to how many the bot still carries."""
    kept = lib.storage.KEEP_KINDS + [CHEST]
    items = []
    for s in inventory():
        if s.item not in items and not (keep and matches(s, keep)) and (category(name, s.item) != OTHER or not matches(s, kept)):
            items.append(s.item)
    reason = None
    missing = {}
    for item in items:
        cat = category(name, item)
        for chest in chests(name, cat):
            if has(item) and room(chest, item):
                put(chest, item)
        while has(item):
            r = add_chest(name, cat, radius)
            if not r["ok"]:
                reason = reason or r["reason"]
                missing[item] = count(item)
                break
            put(r["at"], item)
    return result(reason, None, missing)


def add_chest(name, cat, radius=32):
    """Puts a chest at the next slot of depot name's row, facing along the row so it never joins its neighbour in a
    double chest, and gives it to category cat. A chest already standing there is taken as it is. The chest is got with
    lib.get within radius, so crafted from planks where none is carried or stored. Gives result(): ok with at, the
    chest, or the reason getting or placing it failed with.
    ponytail: a chest standing at the slot is taken whatever it holds."""
    d = depot(name)
    step = FACING[d["facing"]]
    p = d["origin"] + (step[0] * len(d["chests"]), 0, step[2] * len(d["chests"]))
    b = block(p)
    if b is None or b.id != CHEST:
        r = lib.get.get(CHEST, 1, radius)
        if not r["ok"]:
            return r
        placed = place(p, CHEST, d["facing"])
        if not placed:
            return result(placed.reason, p)
    d["chests"].append([cat, p])
    return result(None, p)
