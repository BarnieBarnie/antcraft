# A job: tends a field and stores the harvest, round after round as the crops ripen. Run it as
# /antcraft bot run jobs.farm <x1> <y1> <z1> <x2> <y2> <z2> [crop] [rounds], such as jobs.farm 10 63 10 18 63 18 carrot 0:
# the two corners of the field's soil, crop the one to plant (a crop, seed or harvest of lib.farm.CROPS, wheat by
# default) and rounds how often to tend it (1 by default, 0 until a round fails or the bot is stopped). Each round it
# waters a dry field in its middle with a bucket (pour, which jobs.settle uses too), gets a hoe for the soil to till and
# the seeds for the bare spots with lib.get, harvests, tills and plants (lib.farm.tend), gives back what it borrowed and
# puts the harvest in the nearest chest, keeping what the bot carried of it before the job; between rounds it waits for
# the crops to ripen (docs/cookbook/farm.md). Another script runs it with run(low, high, crop, rounds); imported, this
# runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.farm
import lib.stock
from lib.borrow import borrowed, give_back
from lib.get import ensure_tool, get, make_cost

WAIT = 100  # ticks between looks at the field while the crops grow
# The most ticks an empty bucket may cost to make for the water: a chest's iron, walked to and smelted, not an ore dig.
BUCKET_TICKS = 1000


def bucket_at_hand(radius=32):
    """Whether an empty bucket is carried, in a chest within radius, or made for at most BUCKET_TICKS (a chest's iron),
    so watering a field never digs for iron ore, thousands of ticks (#615)."""
    return (count("bucket") > 0 or len(lib.stock.where("minecraft:bucket", radius)) > 0
            or make_cost("bucket", 1, radius, None, BUCKET_TICKS) is not None)


def pour(at, radius=32):
    """Pours a water source at at, a spot of soil, with a water bucket from lib.get, else an empty bucket from lib.get (a
    chest's, or made of three iron ingots) filled at the nearest water source (#592). With no water source within radius,
    or no bucket at hand, no bucket is got and nothing poured. Gives whether water is at at."""
    if block(at).id == lib.farm.WATER:
        return True
    if (count("water_bucket") or get("water_bucket", 1, radius)["ok"]
            or lib.farm.water(radius) is not None and bucket_at_hand(radius) and get("bucket", 1, radius)["ok"]
            and lib.farm.fill_bucket(radius)["ok"]):
        mine(at)
        use(at, "water_bucket")  # aimed at the hole itself, so the water goes in, not over its rim
    return block(at).id == lib.farm.WATER


def farm(low, high, c, kept):
    """Waters a dry field in its middle (pour), gets the hoe and seeds for crop c (of lib.farm.CROPS), tends the field,
    gives back what it borrowed and stores the harvest beyond what kept, (item, count) pairs, says the bot carried. Gives
    result()."""
    spots = lib.farm.field(low, high)
    if lib.farm.dry(spots):
        pour(spots[len(spots) // 2])  # ponytail: one source in the middle wets a field up to 9 by 9
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


def ripened(low, high):
    """Waits until every crop of the field is ripe; at once where none grows. ponytail: looks every WAIT ticks."""
    while True:
        crops = [s.up() for s in lib.farm.field(low, high) if not block(s.up()).is_air]
        if all([lib.farm.ripe(p) for p in crops]):
            return
        wait(WAIT)


def run(low, high, crop="wheat", rounds=1):
    """Tends the field from low to high, the corners of its soil, rounds times (0: until a round fails), planting crop,
    and gives back what it borrowed. Gives the last round's result(), None for no crop."""
    c = lib.farm.crop(crop)
    if c is None:
        warn("farm: no crop", crop)
        return None
    # What the bot carried of the harvest before the job: it stores only what it carries beyond that (#600).
    kept = [(item, count(item)) for item in [c[2], lib.farm.POISON]]
    try:
        n = 0
        while rounds == 0 or n < rounds:
            if n > 0:
                ripened(low, high)
            r = farm(low, high, c, kept)
            n += 1
            if not r["ok"]:
                warn("farm:", r["reason"], r["missing"])
                break
    finally:
        give_back()
        if borrowed():
            warn("could not give back", borrowed())
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])), Pos(int(args[3]), int(args[4]), int(args[5])),
        args[6] if len(args) > 6 else "wheat", int(args[7]) if len(args) > 7 else 1)
