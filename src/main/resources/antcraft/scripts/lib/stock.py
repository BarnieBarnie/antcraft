# The chests a bot may take from, and fetching from them, for any script: `from lib.stock import chests, where, fetch`.
# A module of the mod's default library (lib/), a skill: it imports lib.borrow and lib.result. What it takes from a chest
# is borrowed (lib.borrow), but what it takes from its colony's core is not: colony storage is the colony's own, not a loan.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.find
from lib.borrow import CHESTS, borrow
from lib.result import result


def chests(radius=32):
    """The chests the bot may take from, nearest first: its colony's core, the positions in memory["chests"], and the
    chests, trapped chests and barrels within radius blocks. A position in another dimension than the bot's is left out,
    and one lib.find.WHERE leaves out."""
    c = colony()
    found = []
    here = dimension()
    for p in ([c["core"]] if c else []) + list(memory.get("chests", [])) + scan(CHESTS, radius=radius):
        if p.dimension in (None, here) and p not in found and lib.find.allowed(p):
            found.append(p)
    return sorted(found, key=distance)


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


def take_up_to(item, n, radius=32):
    """Takes item from the chests that hold it, nearest first, until the bot carries n in total or they hold no more.
    Gives result(): ok, or no_item with missing, how many the bot is still short, keeping what it took."""
    for chest in where(item, radius):
        if count(item) < n:
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
    """Takes up to n of item from chest: borrowed, but from the colony's core plainly."""
    c = colony()
    if c and chest == c["core"]:
        take(chest, item, n)
    else:
        borrow(chest, item, n)


def holds(chest, item):
    return any([matches(s, item) for s in container(chest) or []])


def distance(p):
    return pos().distance(p)
