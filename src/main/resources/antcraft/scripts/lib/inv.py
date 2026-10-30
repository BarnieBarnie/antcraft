# Taking what the bot lacks from a chest and putting the rest away, for any script:
# `from lib.inv import take_all, store_all`, or `import lib.inv` and call lib.inv.take_all(chest, need).
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.shaft import result

# What store_all never puts away: tools and food.
KEPT = ["#minecraft:pickaxes", "#minecraft:axes", "#minecraft:shovels", "#minecraft:hoes", "#minecraft:swords", "#c:foods"]


def take_all(chest, need):
    """Takes from chest until the bot carries each count of need (an item id or a selector such as "#minecraft:planks"
    to how many), as far as the chest has it; gives what is still short, item to how many ({} when nothing is)."""
    short = {}
    for item, n in need.items():
        if count(item) < n and any([matches(s, item) for s in container(chest) or []]):
            take(chest, item, n - count(item))
        if count(item) < n:
            short[item] = n - count(item)
    return short


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
