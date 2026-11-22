# The chests a bot may take from, and fetching from them, for any script: `from lib.stock import chests, where, fetch`.
# A module of the mod's default library (lib/), a skill: it imports lib.borrow, lib.find and lib.result, and lib.storage
# when it takes. What it takes from a chest is borrowed (lib.borrow), but what it takes from its colony's core is not:
# colony storage is the colony's own, not a loan.
# The colony store (STORAGE, store) comes first and lends only tools and weapons; the rest it gives for good (#1019).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.find
from lib.borrow import CHESTS, borrow
from lib.result import result

# Where the colony store is, a Pos, or None: a job's storage argument sets it (storage_arg), as lib.find.WHERE is set
# (#1019). Unset, a colony member uses the store its colony published (colony_memory["storage"], lib.depot.publish_store).
STORAGE = None
# How long store() reuses its scan around STORAGE, in ticks: a chest placed or broken there is seen this soon (#1027).
STORE_TICKS = 100
_scanned = [None, 0, []]  # the STORAGE, game_time() and chests of store()'s last scan


def store():
    """The chests of the colony store: where STORAGE is set, the chests, trapped chests and barrels within 8 blocks of
    it, scanned at most once each STORE_TICKS while STORAGE stays; else those of the depot colony_memory["storage"]
    names; else none."""
    if STORAGE is not None:
        if _scanned[0] != STORAGE or game_time() - _scanned[1] >= STORE_TICKS:
            _scanned[0], _scanned[1], _scanned[2] = STORAGE, game_time(), scan(CHESTS, radius=8, around=STORAGE)
        return list(_scanned[2])
    d = colony_memory.get("depots", {}).get(colony_memory.get("storage")) if colony_memory is not None else None
    return [c[1] for c in d["chests"]] if d else []


def chests(radius=32, scanning=True):
    """The chests the bot may take from: the colony store's first (store), whatever radius and lib.find.WHERE say, then,
    nearest first, its colony's core, the positions in memory["chests"], and, with scanning, the chests, trapped chests
    and barrels within radius blocks (a scan reads the sphere from a block budget all bots share). A position in another
    dimension than the bot's is left out, and one lib.find.WHERE leaves out."""
    c = colony()
    here = dimension()
    found = [p for p in store() if p.dimension in (None, here)]
    first = len(found)
    allowed = lib.find.resolve()
    for p in ([c["core"]] if c else []) + list(memory.get("chests", [])) + (scan(CHESTS, radius=radius) if scanning else []):
        if p.dimension in (None, here) and p not in found and (allowed is None or allowed(p)):
            found.append(p)
    return found[:first] + sorted(found[first:], key=distance)


def storage_arg(args):
    """A job's command-line args without "storage x y z", which sets STORAGE to Pos(x, y, z) (#1019)."""
    if "storage" not in args:
        return args
    import lib.stock  # this module, to set its STORAGE
    i = args.index("storage")
    lib.stock.STORAGE = Pos(int(args[i + 1]), int(args[i + 2]), int(args[i + 3]))
    return args[:i] + args[i + 4:]


def where(item, radius=32):
    """The chests of chests(radius) that hold item (an item id or selector), nearest first."""
    return [chest for chest in chests(radius) if holds(chest, item)]


def fetch(item, n, radius=32):
    """Takes item from the chests that hold it, nearest first, until the bot carries n in total. Gives result(): ok, or
    no_item with missing, how many the chests lack, before it takes anything, or the reason a take failed with."""
    short = fetch_all({item: n - count(item)}, where(item, radius))
    if short:
        return result("no_item", None, short)
    return result()


def take_up_to(item, n, radius=32, chests=None):
    """Takes item from the chests that hold it, nearest first (or those of chests, a list of positions, in turn), until
    the bot carries n in total or they hold no more. Gives result(): ok, or no_item with missing, how many the bot is
    still short, keeping what it took."""
    for chest in (where(item, radius) if chests is None else chests):
        if count(item) < n and holds(chest, item):
            take_from(chest, item, n - count(item))
    return result("no_item", None, {item: n - count(item)}) if count(item) < n else result()


def fetch_all(more, chests):
    """Takes more (item to how many more than the bot carries) from each of chests (a list of positions) in turn; gives
    what is still short, item to how many ({} when nothing is). Where the chests together hold too little of any item
    it takes nothing and gives what they lack."""
    more = dict([(item, k) for item, k in more.items() if k > 0])
    # ponytail: a stack two selectors of more both match counts for each, so overlapping selectors can pass this check
    # and still come up short after taking.
    stacks = []
    for chest in chests:
        stacks += container(chest) or []
    short = {}
    for item, k in more.items():
        k -= sum([s.count for s in stacks if matches(s, item)])
        if k > 0:
            short[item] = k
    if short:
        return short
    need = dict([(item, count(item) + k) for item, k in more.items()])
    for chest in chests:
        for item, total in need.items():
            if count(item) < total and holds(chest, item):
                take_from(chest, item, total - count(item))
    return dict([(item, total - count(item)) for item, total in need.items() if count(item) < total])


def take_from(chest, item, n):
    """Takes up to n of item from chest: borrowed, but from the colony's core plainly, and from the colony store (store)
    plainly but tools and weapons (lib.storage.KEEP_KINDS), which it lends."""
    import lib.storage  # here, as lib.storage imports this module
    c = colony()
    if (c and chest == c["core"]) or (chest in store() and not matches(item, lib.storage.KEEP_KINDS)):
        take(chest, item, n)
    else:
        borrow(chest, item, n)


def holds(chest, item):
    return any([matches(s, item) for s in container(chest) or []])


def distance(p):
    return pos().distance(p)
