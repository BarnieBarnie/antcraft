# A job: guards the bot's home, fighting the hostile mobs that come near it, and retreats home to eat and heal when hurt
# (#705). Run it as /antcraft bot run jobs.guard [radius], such as jobs.guard 24: radius how far from home it fights
# (16 by default). It runs until stopped: each round, under RETREAT health it walks home and eats until it has HEALED
# health (heal), keeps itself fed (lib.food.upkeep), then attacks the mob of MOBS nearest the bot within radius of
# home until it dies or the bot's health drops under RETREAT (#718), or waits at home (docs/cookbook/guard.md). The flee
# reflex, at botFleeHealth (6 by default), stays the last line: a fight it ends with interrupted is followed by the same
# retreat. Another script runs it with run(radius); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.food
from lib.home import go_home
from lib.tools import hold

# The mobs it fights. ponytail: by hand, as no entity tag holds every hostile mob; add a modded mob here.
MOBS = ["#minecraft:zombies", "#minecraft:skeletons", "spider", "cave_spider", "creeper", "witch", "slime", "silverfish",
        "pillager", "vindicator"]
# Health under which it retreats, also from a fight, above the flee reflex's 6, and the health it heals to before it
# fights again.
RETREAT = 12
HEALED = 18
WAIT = 20  # ticks between looks for mobs, and between looks at its health while it heals


def heal():
    """Walks home and eats the best food it carries (lib.food.best; lib.food.upkeep gets some at 14 food or under) while it
    is hurt and not full, until it has HEALED health or can heal no more: under 18 food with nothing left to eat. Gives
    whether it has HEALED health. ponytail: a mob that follows it home is fought back by the bot's own goals once it hurts
    the bot, not by the script."""
    go_home()
    while health() < HEALED:
        f = lib.food.best()
        if food() < 20 and f is not None:
            eat(f[0])
        elif food() < 18:
            return False
        else:
            wait(WAIT)  # 18 food or more heals it, a point every 80 ticks, every 10 with saturation
    return True


def mobs(radius):
    """The mobs of MOBS within radius of home, nearest the bot first."""
    return [m for m in entities(MOBS, 64) if m.pos.distance(home()) <= radius]


def run(radius=16):
    """Guards home until stopped."""
    while True:
        least = RETREAT
        if health() < RETREAT and not heal():  # before upkeep, so it does not eat where it fought
            least = None  # can heal no more: fights on as it is
        lib.food.upkeep()
        near = mobs(radius)
        if near:
            hold("sword")
            r = attack(near[0], min_health=least)
            if not r and r.reason not in ("interrupted", "hurt"):
                wait(WAIT)  # cannot get to it, say: not straight at the next try
        else:
            go_home()
            wait(WAIT)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(int(args[0]) if len(args) > 0 else 16)
