# A job: tends a field and stores the harvest, round after round as the crops ripen. Run it as
# /antcraft bot run jobs.farm <x1> <y1> <z1> <x2> <y2> <z2> [crop] [rounds], such as jobs.farm 10 63 10 18 63 18 carrot 0:
# the two corners of the field's soil, crop the one to plant (a crop, seed or harvest of lib.farm.CROPS, wheat by
# default) and rounds how often to tend it (1 by default, 0 until a round fails or the bot is stopped). Each round it gets
# a hoe for the soil to till and the seeds for the bare spots with lib.get, harvests, tills and plants (lib.farm.tend),
# gives back what it borrowed and puts the harvest in the nearest chest, keeping what the bot carried of it before the job;
# between rounds it waits for the crops to ripen (docs/cookbook/farm.md).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.farm
import lib.stock
from lib.borrow import borrowed, give_back
from lib.get import ensure_tool, get

low = Pos(int(ARGS[0]), int(ARGS[1]), int(ARGS[2]))
high = Pos(int(ARGS[3]), int(ARGS[4]), int(ARGS[5]))
c = lib.farm.crop(ARGS[6] if len(ARGS) > 6 else "wheat")
rounds = int(ARGS[7]) if len(ARGS) > 7 else 1
WAIT = 100  # ticks between looks at the field while the crops grow
# What the bot carried of the harvest before the job: it stores only what it carries beyond that (#600).
kept = [] if c is None else [(item, count(item)) for item in [c[2], lib.farm.POISON]]


def farm():
    """Gets the hoe and seeds, tends the field, gives back what it borrowed and stores the harvest. Gives result()."""
    spots = lib.farm.field(low, high)
    till = len([s for s in spots if block(s).id in lib.farm.SOIL and block(s.up()).is_air])
    if till:
        r = ensure_tool("hoe", till)
        if not r["ok"]:
            return r
    bare = len([s for s in spots if block(s.up()).is_air])
    if bare and count(c[1]) < bare:
        # ponytail: a field short of seeds is still tended, its ripe crops replanted from their own drops; tend says
        # what it lacked.
        get(c[1], bare)
    r = lib.farm.tend(low, high, c[1])
    # Carrots and potatoes are their own seed: give the borrowed ones back from the harvest before storing it, or the
    # chest they came from loses them.
    give_back()
    chests = lib.stock.chests()
    for item, held in kept:
        n = count(item) - held
        if chests and n > 0:
            put(chests[0], item, n)
    return r


def ripened():
    """Waits until every crop of the field is ripe; at once where none grows. ponytail: looks every WAIT ticks."""
    while True:
        crops = [s.up() for s in lib.farm.field(low, high) if not block(s.up()).is_air]
        if all([lib.farm.ripe(p) for p in crops]):
            return
        wait(WAIT)


if c is None:
    warn("farm: no crop", ARGS[6])
else:
    try:
        n = 0
        while rounds == 0 or n < rounds:
            if n > 0:
                ripened()
            r = farm()
            n += 1
            if not r["ok"]:
                warn("farm:", r["reason"], r["missing"])
                break
    finally:
        give_back()
        if borrowed():
            warn("could not give back", borrowed())
