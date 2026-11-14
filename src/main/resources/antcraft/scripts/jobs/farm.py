# A job: tends a field once and stores the harvest. Run it as /antcraft bot run jobs.farm <x1> <y1> <z1> <x2> <y2> <z2> [crop],
# such as jobs.farm 10 63 10 18 63 18 carrot: the two corners of the field's soil, crop the one to plant (a crop, seed or
# harvest of lib.farm.CROPS, wheat by default). It gets a hoe for the soil to till and the seeds for the bare spots with
# lib.get, harvests, tills and plants (lib.farm.tend), puts the harvest in the nearest chest and gives back what it
# borrowed (docs/cookbook/farm.md).
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


def farm():
    """Gets the hoe and seeds, tends the field and stores the harvest. Gives result()."""
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
    chests = lib.stock.chests()
    for item in [c[2], lib.farm.POISON]:
        if chests and has(item):
            put(chests[0], item)
    return r


if c is None:
    warn("farm: no crop", ARGS[6])
else:
    try:
        r = farm()
        if not r["ok"]:
            warn("farm:", r["reason"], r["missing"])
    finally:
        give_back()
        if borrowed():
            warn("could not give back", borrowed())
