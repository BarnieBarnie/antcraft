# Putting away and sorting, for any script: `import lib.storage`, then lib.storage.deposit() after a trip, or
# lib.storage.sort(chests) over a few chests (docs/reference/modules/lib/storage.md).
# A module of the mod's default library (lib/), a skill: it imports lib.borrow, lib.inv, lib.result and lib.stock.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.stock
from lib.borrow import give_back
from lib.inv import KEPT
from lib.result import result

# What deposit never puts away: tools, weapons, torches and food.
KEEP_KINDS = KEPT + ["minecraft:bow", "minecraft:crossbow", "minecraft:trident", "minecraft:mace", "minecraft:torch"]


def deposit(keep=None, radius=32):
    """Gives back what the bot borrowed (lib.borrow.give_back), then puts everything else it carries but KEEP_KINDS and
    what keep (a list of item ids or selectors) selects into the chests of lib.stock.chests(radius): each item first into
    the chests that already hold it, then into any chest with room, nearest first; a chest with no room for it (room) is
    skipped without walking to it. Gives result(): ok, or no_space with missing, item to how many the bot still carries
    as no chest took them."""
    give_back()
    kept = KEEP_KINDS + (keep or [])
    chests = lib.stock.chests(radius)
    items = []
    for s in inventory():
        if s.item not in items and not matches(s, kept):
            items.append(s.item)
    missing = {}
    for item in items:
        holding = [c for c in chests if lib.stock.holds(c, item)]
        for chest in holding + [c for c in chests if c not in holding]:
            if has(item) and room(chest, item):
                put(chest, item)
        if has(item):
            missing[item] = count(item)
    return result("no_space" if missing else None, None, missing)


def sort(chests):
    """Moves the items of chests (a list of positions) so each item sits in as few of them as it can: all of an item into
    the chest that holds most of it, as far as that has room (room): the bot takes only what fits, and walks to no chest
    once that is full. Gives result(): ok, or no_space with missing, item to how many the bot was left carrying as no
    chest took them back.
    ponytail: greedy, one target chest per item, so an item that fills its target stays split over the rest; the bot
    needs a free slot or two to carry each item over."""
    items = []
    for chest in chests:
        for s in container(chest) or []:
            if s.item not in items:
                items.append(s.item)
    missing = {}
    for item in items:
        holding = sorted([c for c in chests if inside(c, item)], key=lambda c: -inside(c, item))
        for chest in holding[1:]:
            fits = min(inside(chest, item), room(holding[0], item) or 0)
            if fits == 0:
                continue
            had = count(item)
            take(chest, item, fits)
            n = count(item) - had
            if n > 0:
                n -= put(holding[0], item, n).value or 0
            if n > 0:
                n -= put(chest, item, n).value or 0
            if n > 0:
                missing[item] = missing.get(item, 0) + n
    return result("no_space" if missing else None, None, missing)


def inside(chest, item):
    """How many of item (an item id) chest holds."""
    return sum([s.count for s in container(chest) or [] if s.item == item])
