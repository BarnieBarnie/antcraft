# Putting what the bot carries away, for any script: `from lib.inv import store_all`, then store_all(chest).
# take_all is kept for old scripts; lib.stock.take_up_to takes from a chest.
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.result import result
from lib.stock import take_up_to

# What store_all never puts away: tools and food.
KEPT = ["#minecraft:pickaxes", "#minecraft:axes", "#minecraft:shovels", "#minecraft:hoes", "#minecraft:swords", "#c:foods"]


def take_all(chest, need):
    """Kept for old scripts: lib.stock.take_up_to(item, n, chests=[chest]) for each item to n of need; gives what is
    still short, item to how many ({} when nothing is)."""
    return dict([(item, n - count(item)) for item, n in need.items() if not take_up_to(item, n, chests=[chest])["ok"]])


def store_all(chest, keep=None):
    """Puts everything the bot carries into chest but tools, food and what keep (a list of item ids or selectors)
    selects. Gives result(): ok, no_space with missing, item to how many the bot still carries of what did not fit, or
    the reason put failed with."""
    kept = KEPT + (keep or [])
    items = []
    for s in inventory():
        if s.item not in items and not matches(s, kept):
            items.append(s.item)
    for i, item in enumerate(items):
        r = put(chest, item)
        if not r and r.reason == "no_space":
            return result("no_space", None, dict([(left, count(left)) for left in items[i:]]))
        if not r:
            return result(r.reason)
    return result()
