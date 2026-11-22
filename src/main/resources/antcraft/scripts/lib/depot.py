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
    """Puts everything the bot carries but what keep selects into the chests of depot name, and of what no category
    selects (OTHER) also not lib.storage.KEEP_KINDS or chests, so an apples category takes apples though they are food:
    each item into the chests of its category with room, in row order; where they are full, a new chest at the next slot
    of the row (add_chest) for the category. keep is a list of item ids or selectors, all of which the bot keeps, and of
    (selector, n) pairs, of which it keeps n in all and stores the rest, such as ("minecraft:dirt", 64) (#762), so
    ("#minecraft:pickaxes", 1) keeps one pickaxe, not one of each kind (#1010); the item held when store began counts
    first and is held after it, so a keep of (selector, 1) keeps it (#1000). Gives result():
    ok, or the reason adding a chest failed with and missing, item to how many the bot still carries beyond its keep."""
    whole = [k for k in keep or [] if isinstance(k, str)]
    capped = [k for k in keep or [] if not isinstance(k, str)]
    kept = lib.storage.KEEP_KINDS + [CHEST]
    items = []
    for s in inventory():
        if s.item not in items and not (whole and matches(s, whole)) and (category(name, s.item) != OTHER or not matches(s, kept)):
            items.append(s.item)
    reason = None
    missing = {}
    hand = held()
    if hand is not None and hand.item in items:
        items = [hand.item] + [i for i in items if i != hand.item]
    left = [k[1] for k in capped]  # what each (selector, n) still keeps, of all it selects (#1010)
    for item in items:
        cap = 0
        for i, k in enumerate(capped):
            if matches(item, k[0]):
                n = min(left[i], count(item) - cap)
                cap += n
                left[i] -= n
        cat = category(name, item)
        for chest in chests(name, cat):
            if count(item) > cap and room(chest, item):
                put(chest, item, count(item) - cap)
        while count(item) > cap:
            r = add_chest(name, cat, radius)
            # Placing the chest moved the held item into the inventory, where put would store it before the rest (#1000).
            if hand is not None:
                equip(hand)
            if not r["ok"]:
                reason = reason or r["reason"]
                missing[item] = count(item) - cap
                break
            # The chest may be crafted from the very logs being stored, leaving none over the cap (#783).
            if count(item) > cap:
                put(r["at"], item, count(item) - cap)
    return result(reason, None, missing)


SKIPS = 8  # slots add_chest skips at most a call
# add_chest calls a depot skips at most after a failed get (#787): each failure doubles them, 1, 2, 4 up to this.
BACKOFF = 16


def make_room(name, free, keep=None, radius=32):
    """Makes room in the bot's inventory for what a job gathers next (#765): where it has fewer than free empty slots,
    stores what it carries in depot name (store, with keep and radius). Gives result(): ok where it has free empty slots
    then, else the reason store gave, or full where it stored all it may and still has too few, with missing, what it
    still carries beyond its keep. A job that gets no ok stops gathering rather than have what it gathers dropped."""
    if free_slots() >= free:
        return result()
    r = store(name, keep, radius)
    if free_slots() >= free:
        return result()
    return result(r["reason"] or "full", None, r["missing"])


def add_chest(name, cat, radius=32):
    """Puts a chest at the next slot of depot name's row, facing along the row so it never joins its neighbour in a
    double chest, and gives it to category cat. A chest already standing there is taken if it is empty or holds only
    items of cat. A slot holding another chest, or a block place may not replace (blocked), is skipped for good, at most
    SKIPS a call (then blocked): the depot never digs, so it leaves what someone else put there. The chest is got with
    lib.get within radius, so crafted from planks where none is carried or stored. After a failed get the next calls skip
    it and fail with its reason, 1, 2, 4 calls up to BACKOFF, unless the bot carries the logs or planks for a chest; a
    chest got ends the backoff (#787). Gives result(): ok with at, the chest, or the reason getting or placing it failed
    with."""
    d = depot(name)
    step = FACING[d["facing"]]
    # ponytail: at most SKIPS slots skipped a call, so a wall along the row ends it with blocked, not a walk down it.
    found = False
    start = d.get("next", len(d["chests"]))
    for _ in range(SKIPS + 1):
        n = d.get("next", len(d["chests"]))
        p = d["origin"] + (step[0] * n, 0, step[2] * n)
        b = block(p)
        if b is not None and b.id == CHEST:
            if all([category(name, s.item) == cat for s in container(p)]):
                found = True
                break
        else:
            # ponytail: a backoff in add_chest calls, not ticks, so a job that stores each round backs off in rounds.
            if d.get("skip") and 4 * count("#minecraft:logs") + count("#minecraft:planks") < 8:
                d["skip"] -= 1
                return result(d["failed"], p)
            r = lib.get.get(CHEST, 1, radius)
            if not r["ok"]:
                d["backoff"] = min(BACKOFF, 2 * d.get("backoff", 0) or 1)
                d["skip"] = d["backoff"]
                d["failed"] = r["reason"]
                return r
            d.pop("skip", None)
            d.pop("backoff", None)
            placed = place(p, CHEST, d["facing"])
            if placed:
                found = True
                break
            if placed.reason != "blocked":
                return result(placed.reason, p)
        d["next"] = n + 1
    if not found:
        d["next"] = start
        return result("blocked", p)
    d["next"] = n + 1
    d["chests"].append([cat, p])
    return result(None, p)
