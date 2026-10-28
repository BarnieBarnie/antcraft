# What the colony scripts (colony.lumberjack, colony.hauler, colony.builder,
# colony.farmer, colony.miner) share: `from colony.common import core, store`.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.


def core():
    """The colony's Colony Core, where members store what they bring in. A bot in no colony says so and stops."""
    c = colony()
    if c is None:
        say("I am in no colony: place a Colony Core and let me join it")
        stop()
    return c["core"]


def in_colony(p):
    """Whether p lies within the colony's radius of its core."""
    c = colony()
    return c is not None and c["core"].distance(p) <= c["radius"]


def store(item):
    """Puts all the bot carries of item into the core; gives how many went in."""
    if not has(item):
        return 0
    return put(core(), item).value or 0


def fetch(item, n):
    """Takes items from the core until the bot carries n of item (an item id or a selector such as "#minecraft:planks"),
    as far as the core has them; gives whether it does."""
    if count(item) < n and any([matches(s, item) for s in container(core()) or []]):
        take(core(), item, n - count(item))
    return count(item) >= n
