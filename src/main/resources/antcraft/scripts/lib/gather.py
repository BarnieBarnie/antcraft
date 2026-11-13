# Gathering raw items and making things from nothing, for any script: `import lib.gather`, then
# lib.gather.make("stone_axe").
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.craft
import lib.dig
import lib.find
import lib.trees
from lib.result import result

from lib.dig import COBBLESTONE, STONE
# How often make plans and gathers again before it crafts with what it has.
ROUNDS = 5


def is_log(item):
    """Whether item, a raw item id plan gives or a selector, means a log: any log does, as planks come from any."""
    return item == lib.trees.LOGS or item.endswith("_log")


def gather(item, n=1, radius=32):
    """Makes the bot carry n more of a raw item it knows how to get within radius: a log (any log, through
    lib.trees.logs) or cobblestone (stone). Gives result(): ok, the reason it failed with missing, or no_recipe with
    missing for an item it does not know how to get."""
    if is_log(item):
        return lib.trees.logs(n, radius)
    if item in ("cobblestone", COBBLESTONE):
        return stone(n, radius)
    return result("no_recipe", None, {item: n})


def exposed_stone(radius=32, skip=None):
    """The nearest exposed stone within radius, at or above the bot's feet first (lib.find.exposed); None if there is
    none. Leaves out the positions in the dict skip."""
    return lib.find.exposed(STONE, radius, skip)


def stone(n, radius=32):
    """lib.dig.stone(n, radius), making a wooden pickaxe first where the bot carries no pickaxe. Gives result(): ok,
    make's result for the pickaxe, or lib.dig.stone's."""
    if not count("#minecraft:pickaxes"):
        r = make("wooden_pickaxe")
        if not r["ok"]:
            return r
    return lib.dig.stone(n, radius)


def make(item, n=1, radius=32):
    """Makes the bot carry n more of item from what it carries and what it gathers within radius: gathers what
    lib.craft.plan(item, n) lists, and plans again, until nothing is missing, then lib.craft.craft_here(item, n). Gives
    result(): ok, the result of the first gather that failed, or craft_here's."""
    for attempt in range(ROUNDS):
        need = lib.craft.plan(item, n)["gather"]
        if not need:
            break
        for raw, k in need.items():
            r = gather(raw, k, radius)
            if not r["ok"]:
                return r
    return lib.craft.craft_here(item, n)
