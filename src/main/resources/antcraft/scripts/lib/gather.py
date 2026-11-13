# Gathering raw items and making things from nothing, for any script: `import lib.gather`, then
# lib.gather.make("stone_axe").
# A module of the mod's default library (lib/): wrappers of lib.get that take "n more" where get takes a total.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.find
import lib.get
import lib.trees

from lib.dig import COBBLESTONE, STONE


def is_log(item):
    """Whether item, a raw item id plan gives or a selector, means a log: any log does, as planks come from any."""
    return item == lib.trees.LOGS or item.endswith("_log")


def more(item, n, radius):
    """lib.get.get for n more of item than the bot carries."""
    return lib.get.get(item, count(lib.get.key(item)) + n, radius)


def gather(item, n=1, radius=32):
    """Makes the bot carry n more of item within radius, through lib.get.get. Gives get's result()."""
    return more(item, n, radius)


def exposed_stone(radius=32, skip=None):
    """The nearest exposed stone within radius, at or above the bot's feet first (lib.find.exposed); None if there is
    none. Leaves out the positions in the dict skip."""
    return lib.find.exposed(STONE, radius, skip)


def stone(n, radius=32):
    """n more cobblestone through lib.get.get, a pickaxe made first where the bot carries none. Gives get's result()."""
    return more(COBBLESTONE, n, radius)


def make(item, n=1, radius=32):
    """Makes the bot carry n more of item from what it carries, the chests within radius and what it gathers there,
    through lib.get.get. Gives get's result()."""
    return more(item, n, radius)
