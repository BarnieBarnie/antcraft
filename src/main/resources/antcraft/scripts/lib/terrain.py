# Terrain work, for any script: `import lib.terrain`, then lib.terrain.flatten(low, high, y) levels a plot at y before
# building on it; jobs.flatten is built on it (docs/reference/modules/lib/terrain.md).
# A module of the mod's default library (lib/), a skill over lib.area. It assumes the tools to dig with and the fill to
# place; lib.get gets those.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.area
from lib.result import result

# The tools whose #minecraft:mineable/<kind> tag needs counts.
KINDS = ["shovel", "pickaxe", "axe"]


def holes(low, high, y):
    """The holes to fill, one (bottom, top) per column of the box from low to high (any two opposite corners) that is
    air at y: top at y, bottom the lowest air under it, down to solid ground or the box's low y."""
    low = Pos(low)
    high = Pos(high)
    spans = []
    for z in range(min(low.z, high.z), max(low.z, high.z) + 1):
        for x in range(min(low.x, high.x), max(low.x, high.x) + 1):
            h = y
            while h >= min(low.y, high.y) and block(Pos(x, h, z)) is not None and block(Pos(x, h, z)).is_air:
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
    """Mines every block of the box above y (lib.area.clear), then fills each hole at y down to solid ground with fill
    (lib.area.fill), so the plot's top is level at y. Gives result(): ok, the reason of the mine or place that failed
    with at, or missing_ingredients with missing {fill: blocks left to place} once the bot runs out of fill."""
    low = Pos(low)
    high = Pos(high)
    if max(low.y, high.y) > y:
        r = lib.area.clear(Pos(low.x, y + 1, low.z), Pos(high.x, max(low.y, high.y), high.z))
        if not r["ok"]:
            return r
    spans = holes(low, high, y)
    for i in range(len(spans)):
        r = lib.area.fill(spans[i][0], spans[i][1], fill)
        if not r["ok"]:
            if r["reason"] == "missing_ingredients":
                left = r["missing"][fill] + sum([t2.y - b2.y + 1 for b2, t2 in spans[i + 1:]])
                return result("missing_ingredients", r["at"], {fill: left})
            return r
    return result()
