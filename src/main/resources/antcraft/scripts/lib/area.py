# Working a cuboid in one call, for any script: `import lib.area`, then
# lib.area.clear(a, b), lib.area.fill(a, b, "cobblestone") or lib.area.box(a, b).
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.retry
from lib.shaft import result


def box(a, b):
    """Every position of the cuboid with corners a and b (in any order) once: bottom up, each layer in rows along x,
    one row after the other along z. reversed(box(a, b)) is top down. The positions are in the bot's dimension."""
    a = Pos(a)
    b = Pos(b)
    ps = []
    for y in range(min(a.y, b.y), max(a.y, b.y) + 1):
        for z in range(min(a.z, b.z), max(a.z, b.z) + 1):
            for x in range(min(a.x, b.x), max(a.x, b.x) + 1):
                ps.append(Pos(x, y, z))
    return ps


def clear(a, b):
    """Mines every block of the cuboid but air and fluids, top down; the drops go into the bot's inventory. Gives
    result(): ok, or the reason of the mine that failed with at, where."""
    for p in reversed(box(a, b)):
        k = block(p)
        if k is None:
            return result("not_loaded", p)
        if k.is_air or k.is_fluid:
            continue
        r = lib.retry.retry(mine, (p,))
        if not r and r.reason != "target_gone":
            return result(r.reason, p)
    return result()


def fill(a, b, item):
    """Places item at every air position of the cuboid, bottom up. Gives result(): ok, missing_ingredients with at, the
    first position left, and missing, {item: how many positions are left}, once the bot runs out of item, or the reason
    of the place that failed with at."""
    left = [p for p in box(a, b) if block(p) is None or block(p).is_air]
    for i, p in enumerate(left):
        if not has(item):
            return result("missing_ingredients", p, {item: len(left) - i})
        r = lib.retry.retry(place, (p, item))
        if not r:
            return result(r.reason, p)
    return result()
