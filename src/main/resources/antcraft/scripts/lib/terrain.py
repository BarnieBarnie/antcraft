# Terrain work, for any script: `import lib.terrain`, then lib.terrain.flatten(low, high, y) levels a plot at y before
# building on it; jobs.flatten is built on it (docs/reference/modules/lib/terrain.md).
# A module of the mod's default library (lib/), a skill over lib.area. It assumes the tools to dig with and the fill to
# place; lib.get gets those.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.area
import lib.retry
from lib.result import result

# The tools whose #minecraft:mineable/<kind> tag needs counts.
KINDS = ["shovel", "pickaxe", "axe"]
# The fluids a hole may hold; fill replaces them. A waterlogged block is no hole.
FLUIDS = ["minecraft:water", "minecraft:lava"]


def _open(k):
    """Whether fill goes at the block k: air, water or lava."""
    return k is not None and (k.is_air or k.id in FLUIDS)


def holes(low, high, y):
    """The holes to fill, one (bottom, top) per column of the box from low to high (any two opposite corners) that is
    air, water or lava at y: top at y, bottom the lowest such block under it, down to solid ground or the box's low y."""
    low = Pos(low)
    high = Pos(high)
    spans = []
    for z in range(min(low.z, high.z), max(low.z, high.z) + 1):
        for x in range(min(low.x, high.x), max(low.x, high.x) + 1):
            h = y
            while h >= min(low.y, high.y) and _open(block(Pos(x, h, z))):
                h -= 1
            if h < y:
                spans.append((Pos(x, h + 1, z), Pos(x, y, z)))
    return spans


def digs(low, high, y):
    """The blocks of the box above y to mine: all but air and fluids."""
    low = Pos(low)
    high = Pos(high)
    top = max(low.y, high.y)
    if top <= y:
        return []
    ps = lib.area.box(Pos(low.x, y + 1, low.z), Pos(high.x, top, high.z))
    return [p for p in ps if block(p) is not None and not block(p).is_air and not block(p).is_fluid]


def needs(low, high, y):
    """What flatten(low, high, y) takes: a dict of fill, the blocks it places, dig, the blocks it mines, and tools,
    {kind: blocks} of those by the KINDS tag they are mineable with. A finder, done at once."""
    dug = digs(low, high, y)
    tools = {}
    for p in dug:
        for k in KINDS:
            if f"#minecraft:mineable/{k}" in block(p).tags:
                tools[k] = tools.get(k, 0) + 1
    return {"fill": sum([t.y - b.y + 1 for b, t in holes(low, high, y)]), "dig": len(dug), "tools": tools}


def flatten(low, high, y, fill="minecraft:dirt"):
    """Mines every block of the box above y (lib.area.clear), then fills each hole at y down to solid ground with fill,
    layer by layer bottom up, water and lava sources first in each layer, so the plot's top is level at y. fill is an item
    or a list of items, of which each block takes the first the bot has. Gives result(): ok, the reason of the mine or
    place that failed with at, or missing_ingredients with missing {fill (the first of a list): blocks left to place}
    once the bot runs out of fill."""
    fills = [fill] if isinstance(fill, str) else fill
    low = Pos(low)
    high = Pos(high)
    if max(low.y, high.y) > y:
        r = lib.area.clear(Pos(low.x, y + 1, low.z), Pos(high.x, max(low.y, high.y), high.z))
        if not r["ok"]:
            return r
    ps = []
    for b, t in holes(low, high, y):
        ps.extend(lib.area.box(b, t))
    # A source filled first stops feeding the flowing water around it.
    ps = sorted(ps, key=lambda p: (p.y, 0 if block(p).state.get("level", 1) == 0 else 1))
    for i in range(len(ps)):
        # Read again: flowing water comes and goes as the holes fill.
        if not _open(block(ps[i])):
            continue
        item = None
        for f in fills:
            if item is None and has(f):
                item = f
        if item is None:
            return result("missing_ingredients", ps[i], {fills[0]: len(ps) - i})
        r = lib.retry.retry(place, (ps[i], item))
        if not r:
            return result(r.reason, ps[i])
    return result()
