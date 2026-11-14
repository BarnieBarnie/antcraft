# A job: levels a plot before building on it. Run it as /antcraft bot run jobs.flatten <x1> <z1> <x2> <z2> [y] [fill],
# such as jobs.flatten 10 10 18 18 64 cobblestone: the two corners of the plot, the height of its top, by default the
# middle (median) height of its ground, and the block to fill with, by default whichever of FILLS the bot carries or
# digs, getting dirt once it has none of them. It digs everything above y, up to
# the highest ground of the plot but no more than TALL over y, and fills the holes at y (air, water or lava) down to
# solid ground, getting the tools and the fill it lacks with lib.get, and gives back what it borrowed
# (docs/reference/modules/lib/terrain.md). Another script runs it with run(x1, z1, x2, z2, y, fill); imported, this
# runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.terrain
from lib.borrow import borrowed, give_back
from lib.get import ensure_tool, get

TALL = 16  # ponytail: a hillside higher than this over y is left standing; flatten it in steps

# The fill without one given, dirt first: what the bot carries or digs before it gets dirt (#597).
FILLS = ["minecraft:dirt", "minecraft:cobblestone", "minecraft:stone"]


def flatten(low, high, y, fill):
    """Gets the tools, flattens, and gets the fill the dug blocks fell short of and fills again. Gives result()."""
    n = lib.terrain.needs(low, high, y)
    for kind, uses in n["tools"].items():
        # The hand digs dirt, sand and wood, so a shovel or axe only where it beats the hand.
        r = ensure_tool(kind, uses, None if kind == "pickaxe" else "hand")
        if not r["ok"]:
            return r
    r = lib.terrain.flatten(low, high, y, fill)
    if r["reason"] == "missing_ingredients":
        for item, n in r["missing"].items():
            g = get(item, n)
            if not g["ok"]:
                return g
        r = lib.terrain.flatten(low, high, y, fill)
    return r


def run(x1, z1, x2, z2, y=None, fill=None):
    """Levels the plot from x1 z1 to x2 z2 at y (the median height of its ground where None) with fill (FILLS where None),
    and gives back what it borrowed. Gives flatten's result()."""
    ground = []
    for z in range(min(z1, z2), max(z1, z2) + 1):
        for x in range(min(x1, x2), max(x1, x2) + 1):
            s = surface(Pos(x, pos().y, z))
            if s is not None:
                ground.append(s.y - 1)
    ground.sort()
    if y is None:
        y = ground[len(ground) // 2]
    low = Pos(x1, min(ground[0], y), z1)
    high = Pos(x2, max(min(ground[-1], y + TALL), y), z2)
    try:
        r = flatten(low, high, y, fill or FILLS)
        if not r["ok"]:
            warn("flatten:", r["reason"], r["at"], r["missing"])
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
    rest = args[4:]
    y = int(rest.pop(0)) if rest and rest[0][0] in "-0123456789" else None
    run(int(args[0]), int(args[1]), int(args[2]), int(args[3]), y, rest[0] if rest else None)
