# A job: levels a plot before building on it. Run it as /antcraft bot run jobs.flatten <x1> <z1> <x2> <z2> [y] [fill],
# such as jobs.flatten 10 10 18 18 64 cobblestone: the two corners of the plot, the height of its top, by default the
# middle (median) height of its ground, and the block to fill with, dirt by default. It digs everything above y, up to
# the highest ground of the plot but no more than TALL over y, and fills the holes at y (air, water or lava) down to
# solid ground, getting the tools and the fill it lacks with lib.get, and gives back what it borrowed
# (docs/reference/modules/lib/terrain.md).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.terrain
from lib.borrow import borrowed, give_back
from lib.get import ensure_tool, get

TALL = 16  # ponytail: a hillside higher than this over y is left standing; flatten it in steps

x1, z1, x2, z2 = int(ARGS[0]), int(ARGS[1]), int(ARGS[2]), int(ARGS[3])
rest = ARGS[4:]
y = None
if rest and rest[0][0] in "-0123456789":
    y = int(rest.pop(0))
FILL = rest[0] if rest else "minecraft:dirt"
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


def flatten():
    """Gets the tools, flattens, and gets the fill the dug blocks fell short of and fills again. Gives result()."""
    n = lib.terrain.needs(low, high, y)
    for kind, uses in n["tools"].items():
        # The hand digs dirt, sand and wood, so a shovel or axe only where it beats the hand.
        r = ensure_tool(kind, uses, None if kind == "pickaxe" else "hand")
        if not r["ok"]:
            return r
    r = lib.terrain.flatten(low, high, y, FILL)
    if r["reason"] == "missing_ingredients":
        g = get(FILL, r["missing"][FILL])
        if not g["ok"]:
            return g
        r = lib.terrain.flatten(low, high, y, FILL)
    return r


try:
    r = flatten()
    if not r["ok"]:
        warn("flatten:", r["reason"], r["at"], r["missing"])
finally:
    give_back()
    if borrowed():
        warn("could not give back", borrowed())
