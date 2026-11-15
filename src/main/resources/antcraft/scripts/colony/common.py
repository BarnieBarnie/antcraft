# What the colony scripts (colony.lumberjack, colony.hauler, colony.builder,
# colony.farmer, colony.miner) share: `from colony.common import core, settle, store`. Each calls settle() at its top: a
# home beside the core and, after a death, its drops picked up and its jobs given back (#689). They take from the core with
# lib.stock.take_up_to(item, n, chests=[core()]); fetch(item, n) is kept for old scripts.
# The library keeps within the colony by itself (lib.find.WHERE is colony_edge, #594): lib.get, lib.stock, lib.trees and
# lib.dig neither take from a chest nor gather a block outside the colony's radius of its core, a column from the build
# limit down to bedrock, and a bot that leaves its colony mid-run is no longer kept to it (#625).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
import lib.find
import lib.stock
from lib.find import in_colony  # kept for `from colony.common import in_colony`


def core():
    """The colony's Colony Core, where members store what they bring in. A bot in no colony says so and stops."""
    c = colony()
    if c is None:
        say("I am in no colony: place a Colony Core and let me join it")
        stop()
    return c["core"]


def home_spot():
    """A free standing spot beside the core: air at the feet and head over a block that is neither air nor fluid (#690).
    ponytail: the core's four sides only, else on top of it; a block put there after set_home is not seen."""
    c = core()
    for d in [(1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1)]:
        p = c + d
        under = block(p.down())
        if block(p).is_air and block(p.up()).is_air and not under.is_air and not under.is_fluid:
            return p
    return c.up()


def settle():
    """Call it at the top of a colony script: sets the bot's home beside the core (home_spot), so a member that dies
    comes back in the colony, and after a death takes back its scaffold and picks up its drops (lib.trees.recover) and
    gives back the jobs it held, as the run that took them is gone, so it takes them up again (#689)."""
    home = home_spot()  # a bot in no colony stops here, before it loads lib.trees
    import lib.trees

    set_home(home)
    if lib.trees.recover():
        for j in jobs():
            if j["taker"] is not None:
                drop_job(j["id"])  # False for another member's job


def store(item):
    """Puts all the bot carries of item into the core; gives how many went in."""
    if not has(item):
        return 0
    return put(core(), item).value or 0


def fetch(item, n):
    """Kept for old scripts: whether lib.stock.take_up_to(item, n, chests=[core()]) got the bot n of item."""
    return lib.stock.take_up_to(item, n, chests=[core()])["ok"]

