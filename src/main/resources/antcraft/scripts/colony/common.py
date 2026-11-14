# What the colony scripts (colony.lumberjack, colony.hauler, colony.builder,
# colony.farmer, colony.miner) share: `from colony.common import core, store`. They take from the core with
# lib.stock.take_up_to(item, n, chests=[core()]); fetch(item, n) is kept for old scripts.
# Importing it keeps the library within the colony: lib.find.WHERE is in_colony, so lib.get, lib.stock, lib.trees and
# lib.dig neither take from a chest nor gather a block outside the colony's radius of its core,
# a column from the build limit down to bedrock.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
import lib.find
import lib.stock


def core():
    """The colony's Colony Core, where members store what they bring in. A bot in no colony says so and stops."""
    c = colony()
    if c is None:
        say("I am in no colony: place a Colony Core and let me join it")
        stop()
    return c["core"]


def in_colony(p):
    """Whether p lies within the colony's radius of its core, measured across: the edge is a column, so a deep block
    under the colony is in it (#576)."""
    c = colony()
    if c is None:
        return False
    p = Pos(p)
    dx = p.x - c["core"].x
    dz = p.z - c["core"].z
    return dx * dx + dz * dz <= c["radius"] * c["radius"]


def store(item):
    """Puts all the bot carries of item into the core; gives how many went in."""
    if not has(item):
        return 0
    return put(core(), item).value or 0


def fetch(item, n):
    """Kept for old scripts: whether lib.stock.take_up_to(item, n, chests=[core()]) got the bot n of item."""
    return lib.stock.take_up_to(item, n, chests=[core()])["ok"]


lib.find.WHERE = in_colony
