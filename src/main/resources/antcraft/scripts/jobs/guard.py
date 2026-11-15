# A job: guards the bot's home, fighting the hostile mobs that come near it, and retreats home to eat and heal when hurt
# (#705). Run it as /antcraft bot run jobs.guard [radius], such as jobs.guard 24: radius how far from home it fights
# (16 by default). It runs until stopped: each round, under RETREAT health it walks home and eats until it has HEALED
# health (heal), keeps itself fed (lib.food.upkeep), then attacks the mob of MOBS nearest the bot within radius of
# home until it dies or the bot's health drops under RETREAT (#718), or waits at home (docs/cookbook/guard.md); a mob it
# cannot get to it shoots, with a bow and arrows, from where it stands (#756), or else leaves alone for SKIP ticks
# (#745). The flee reflex, at botFleeHealth (6 by default), stays the last line: a fight it ends with interrupted is
# followed by the same retreat. Another script runs it with run(radius); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.food
from lib.home import go_home
from lib.tools import hold

# The mobs it fights: the game's monsters (monsters(), modded ones too, #722) but LEFT_OUT, the neutral ones, which a
# blow turns on the bot with their kin, the warden and the bosses.
LEFT_OUT = ["minecraft:enderman", "minecraft:zombified_piglin", "minecraft:piglin", "minecraft:warden",
            "minecraft:wither", "minecraft:ender_dragon"]
MOBS = [m for m in monsters() if m not in LEFT_OUT]
# Health under which it retreats, also from a fight, above the flee reflex's 6, and the health it heals to before it
# fights again.
RETREAT = 12
HEALED = 18
WAIT = 20  # ticks between looks for mobs, and between looks at its health while it heals
SKIP = 600  # ticks it leaves a mob it could not get to, nor shoot, alone (#745)
skipped = {}  # the id of each such mob: the game time it tries it again


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


def can_shoot():
    """Whether it carries a bow or crossbow and arrows, all shoot needs (#756)."""
    return has(["bow", "crossbow"]) and has("#minecraft:arrows")


def mobs(radius):
    """The mobs of MOBS within radius of home, nearest the bot first, but those it skips."""
    now = game_time()
    return [m for m in entities(MOBS, 64) if m.pos.distance(home()) <= radius and skipped.get(m.id, 0) <= now]


def run(radius=16):
    """Guards home until stopped."""
    while True:
        least = RETREAT
        if health() < RETREAT and not heal():  # before upkeep, so it does not eat where it fought
            least = None  # can heal no more: fights on as it is
        lib.food.upkeep()
        near = mobs(radius)
        if near:
            if can_shoot() and not reachable(near[0].pos, near=1):
                r = shoot(near[0], min_health=least)  # from where it stands, not from under a flying mob (#756)
            else:
                hold("sword")
                r = attack(near[0], min_health=least)
                if not r and r.reason == "unreachable":
                    r = shoot(near[0], min_health=least)  # a bow reaches what a sword cannot, such as a flying mob
            if not r and r.reason not in ("interrupted", "hurt"):
                now = game_time()
                for i in [i for i in skipped if skipped[i] <= now]:
                    skipped.pop(i)  # so the ids of mobs long dead do not pile up
                skipped[near[0].id] = now + SKIP  # cannot get to it, say: the next mob first
        else:
            go_home()
            wait(WAIT)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(int(args[0]) if len(args) > 0 else 16)
