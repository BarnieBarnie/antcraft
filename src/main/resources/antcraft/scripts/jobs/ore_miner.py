# A job: gathers one ore and keeps it at home (#1002). Run it as /antcraft bot run jobs.ore_miner [ore] [chunks] [radius],
# such as jobs.ore_miner raw_iron: ore is a raw item of lib.dig.ORES, raw_iron by default, chunks how many chunks it
# claims, 9, in a spiral out from the home chunk (#1003), radius how far it looks for chests and the kit, 32. It digs
# stairs from home down to lib.dig.best_y(ore), spiralling within the home chunk, branch-mines the chunk there in a grid
# of branches 3 blocks apart, then tunnels at that level into the next chunk of its claim and mines that, and goes home
# to smelt what it mined and store it in depot "ore" whenever it is nearly full or its pickaxe is low; there it takes a
# better pickaxe from a chest, storing its old one (#1004), as it also does before the first trip. Raw copper, iron and
# gold are smelted to ingots; coal, diamonds, lapis and redstone are stored as they drop (#1005). With its claim
# cleared it goes home and says it is finished (docs/cookbook/ore-miner.md). Another script runs it with
# run(ore, chunks, radius, y, area); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.bootstrap
import lib.craft
import lib.depot
import lib.dig
import lib.enchant
import lib.find
import lib.food
import lib.get
import lib.stock
from jobs.strip_mine import MINER, USES, tier
from lib.borrow import borrowed, give_back
from lib.dig import FACING, turn
from lib.home import go_home
from lib.tools import rank
from lib.result import result

NAME = "ore"  # the depot, memory["depots"]["ore"]
PICKAXES = "#minecraft:pickaxes"
# pickaxes: the old one after a better one from a chest (#1004); tools are never stored as other (lib.storage.KEEP_KINDS).
# gems: what drops as it is, never smelted: diamonds, lapis, redstone and coal, which has no #c tag (#1005).
CATEGORIES = {"ingots": ["#c:ingots"], "ores": ["#c:raw_materials", "#c:ores"], "pickaxes": [PICKAXES],
              "gems": ["#c:gems", "#c:dusts", "minecraft:coal"]}
# What store leaves the bot: its pickaxe, the crafting table to make the next with and the furnace it smelts in; torches
# are kept as lib.storage.KEEP_KINDS.
KEEP = [PICKAXES, "minecraft:crafting_table", "minecraft:furnace"]
FREE = 3  # empty slots below which it goes home: a branch fills one with cobblestone, one with ore and one with coal
SPACING = 3  # blocks between branches, so their walls uncover every block of the level
LENGTH = 16  # the longest branch: across a chunk
FACE = "north"  # the way the stairs set off from home
COAL = "minecraft:coal"
SMELTS = 8  # items one coal smelts
MAX_RADIUS = 32  # the largest radius scan and find_nearest take (BlockScan.MAX_RADIUS)
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}
CLOCKWISE = {"north": "east", "east": "south", "south": "west", "west": "north"}
SPIRAL = [(1, 0), (0, 1), (-1, 0), (0, -1)]  # east, south, west, north: clockwise seen from above
DEPTH = 8  # blocks below home it mines at least, for an ore whose best y is at or above home, such as coal (#1006)


def chunk(p):
    """The columns of p's chunk, (x0, z0, x1, z1)."""
    x = p.x // 16 * 16
    z = p.z // 16 * 16
    return (x, z, x + 15, z + 15)


def claim(area, n):
    """The n areas of a claim: area, then areas of its size round it in a clockwise spiral, each beside the one before."""
    w = area[2] - area[0] + 1
    h = area[3] - area[1] + 1
    out = [tuple(area)]
    x = z = i = 0
    leg = 1
    while len(out) < n:
        for _ in range(2):
            dx, dz = SPIRAL[i % 4]
            i += 1
            for _ in range(leg):
                x += dx
                z += dz
                out.append((area[0] + x * w, area[1] + z * h, area[2] + x * w, area[3] + z * h))
        leg += 1
    return out[:n]


def columns(lo, hi):
    """Where the branches between lo and hi run, SPACING apart from lo + 1, and at hi where the last leaves it bare."""
    cs = list(range(min(lo + 1, hi), hi + 1, SPACING))
    if cs[-1] < hi - 1:
        cs.append(hi)
    return cs


def across(way):
    """Whether the corridor along the bottom of stairs dug towards way runs along x."""
    return way in ("north", "south")


def grid(area, way):
    """The branches of area as "c facing" keys: from the corridor at each of its columns towards way and back."""
    lo, hi = (area[0], area[2]) if across(way) else (area[1], area[3])
    return [f"{c} {f}" for c in columns(lo, hi) for f in (way, OPPOSITE[way])]


def point(c, om):
    """The corridor's block at column c."""
    b = om["bottom"]
    return Pos(c, b.y, b.z) if across(om["way"]) else Pos(b.x, b.y, c)


def inside(areas, prior, guard=()):
    """lib.find.WHERE for the columns of areas that prior allows, but those of guard, (x, z) pairs."""
    return lambda p: (any(a[0] <= p.x <= a[2] and a[1] <= p.z <= a[3] for a in areas) and (p.x, p.z) not in guard
                      and (prior is None or prior(p)))


def where(area, om, prior):
    """lib.find.WHERE for a trip: the columns of area that prior allows, but those of the last two steps of the stairs
    (om["guard"]), so no branch undermines them."""
    return inside([area], prior, [tuple(g) for g in om["guard"]])


def behind(legs, bottom):
    """The columns, [x, z], of the two steps above bottom of stairs dug in legs, (start, facing) pairs."""
    cells = []
    x, z = bottom.x, bottom.z
    for start, way in reversed(legs):
        d = FACING[way]
        while len(cells) < 2 and (x, z) != (start.x, start.z):
            x -= d[0]
            z -= d[2]
            cells.append([x, z])
    return cells


def down(y, area, om, prior):
    """Takes the bot from home to where it mines: the bottom of its stairs, or the end of the tunnel into the chunk it
    mines, it got to before; else new stairs dug to y towards FACE within area, turning clockwise at its edge, so they
    spiral down as deep as they must (lib.dig.turn gives up after four legs). Keeps their bottom, last facing and the
    columns of their last two steps in om. Gives result()."""
    if "bottom" in om:
        r = goto(om["bottom"])
        return result() if r else result(r.reason, om["bottom"])
    lib.find.WHERE = inside([area], prior)
    way = FACE
    legs = [(pos(), way)]
    r = lib.dig.stairs_to(y, way)
    # A turned leg without a step has no room to go on: a second turn would dig back under the last steps.
    while r["reason"] == "outside" and (len(legs) == 1 or pos() != legs[-1][0]):
        way = CLOCKWISE[way]
        legs.append((pos(), way))
        r = lib.dig.stairs_to(y, way)
    if r["ok"]:
        om["bottom"] = pos()
        om["way"] = way
        om["guard"] = behind(legs, pos())
    return r


def trip(item, y, areas, om):
    """Goes down and digs the grid of each area of the claim not cleared yet (the first om["cleared"] are), tunnelling
    from each into the next, until it is nearly full (FREE) or its pickaxe would not last a branch (USES), then walks
    home. Gives result(), with dug where the whole claim is cleared."""
    prior = lib.find.WHERE
    try:
        r = down(y, areas[0], om, prior)
        if not r["ok"]:
            return r
        while om["cleared"] < len(areas):
            k = om["cleared"]
            r = dig(item, areas[k], om, prior)
            if not r["ok"] or "dug" not in r:
                return r
            if k + 1 < len(areas):
                r = tunnel(areas[k], areas[k + 1], om, prior)
                if not r["ok"]:
                    return r
            om["cleared"] = k + 1
        r = result()
        r["dug"] = True
        return r
    finally:
        lib.find.WHERE = prior
        go_home()


def dig(item, area, om, prior):
    """Digs the branches of area's grid not done yet, each from the corridor at its column, until the bot is nearly full
    (FREE) or its pickaxe would not last a branch (USES). Each branch is marked done in om["done"] once dug to the edge
    of area. Gives result(), with dug where every branch is done."""
    lib.find.WHERE = where(area, om, prior)
    b = om["bottom"]
    cur = b.x if across(om["way"]) else b.z
    for key in grid(area, om["way"]):
        if key in om["done"]:
            continue
        if free_slots() < FREE or lib.get.lasting("pickaxe", USES, tier(item))[2] > 0:
            return result()
        c, f = key.split(" ")
        c = int(c)
        if c != cur:
            towards = ("east" if c > cur else "west") if across(om["way"]) else ("south" if c > cur else "north")
            n = abs(c - cur)
            r = lib.dig.branch(n, n + 1, towards)  # no torches along the corridor, which it walks again
            if not r["ok"]:
                return r
            cur = c
        r = lib.dig.branch(LENGTH, facing=f)
        if not r["ok"] and r["reason"] != "outside":
            return r
        om["done"].append(key)
        g = goto(point(c, om))
        if not g:
            return result(g.reason, point(c, om))
    r = result()
    r["dug"] = True
    return r


def tunnel(area, to, om, prior):
    """Digs on at the grid's level from the corridor of area, dug, into the area to beside it: from the corridor's end
    where to lies along it, else from the column farthest from the stairs, along its branch. Where the tunnel enters
    to its grid starts: the corridor through there, square to the tunnel, with no stairs to guard. Gives result()."""
    d = ("east" if to[0] > area[0] else "west") if to[0] != area[0] else ("south" if to[1] > area[1] else "north")
    b = om["bottom"]
    along = across(om["way"])
    lo, hi = (area[0], area[2]) if along else (area[1], area[3])
    cs = columns(lo, hi)
    if along == (d in ("east", "west")):
        c = max(cs) if d in ("east", "south") else min(cs)
    else:
        c = max(cs, key=lambda col: abs(col - (b.x if along else b.z)))
    start = point(c, om)
    n = {"east": to[0] - start.x, "west": start.x - to[2], "south": to[1] - start.z, "north": start.z - to[3]}[d]
    w = goto(start)
    if not w:
        return result(w.reason, start)
    lib.find.WHERE = inside([area, to], prior, [tuple(g) for g in om["guard"]])
    r = lib.dig.branch(n, n + 1, d)
    if r["ok"]:
        om["bottom"] = pos()
        om["way"] = d
        om["guard"] = []
        om["done"] = []
    return r


def better_pickaxe(item, radius):
    """Holds the best pickaxe of the tier item needs or above (lib.enchant.best_pickaxe) of the chests within radius,
    taking only that Stack from a chest (take, #997), where it beats the bot's best. Gives whether it took one."""
    need = tier(item)
    took = False
    for chest in lib.stock.where(PICKAXES, radius):
        mine = lib.enchant.best_pickaxe(inventory(), need)
        there = lib.enchant.best_pickaxe(container(chest) or [], need)
        if there is not None and (mine is None or rank(there) < rank(mine)
                                  or (rank(there) == rank(mine) and lib.enchant.better(there, mine, lib.enchant.PICKAXE))):
            took = bool(take(chest, there)) or took
    if took:
        equip(lib.enchant.best_pickaxe(inventory(), need))
    return took


def unload(item, radius, took=False):
    """Smelts the raw item the bot carries at home (lib.craft.smelt_here), with coal it gets (lib.get) where it carries
    too little (coal, diamonds, lapis and redstone are stored as they drop), takes a better pickaxe from the chests
    (better_pickaxe), then stores all but KEEP in depot NAME; with a better pickaxe taken, now or before the trip (took),
    the pickaxes it held before go too. Gives store's result()."""
    n = count(item) if item.startswith("raw_") else 0
    if n:
        fuel = -(-n // SMELTS)
        if count(COAL) < fuel:
            lib.get.get(COAL, fuel, radius)  # ponytail: where it fails, smelt_here burns any charcoal, logs or planks carried
        r = lib.craft.smelt_here(item, n)
        if not r["ok"]:
            warn("ore miner: could not smelt", item + ":", r["reason"], r["missing"])
    keep = KEEP
    if better_pickaxe(item, radius) or took:
        keep = [(PICKAXES, 1)] + KEEP[1:]  # the held pickaxe counts first (#1010)
    s = lib.depot.store(NAME, keep, radius)
    if not s["ok"]:
        warn("ore miner: no chest has room for", sorted(s["missing"].keys()), "and none to get:", s["reason"])
    return s


def ore_miner(item, y, area, chunks, radius):
    """Gets the kit, then makes trips from home until the grids of the claim of chunks areas from area (claim) at y are
    dug, unloading after each; then, and whenever run again, it is finished. A claim belongs to a home: a bot given a new
    home claims afresh. Gives result(): ok with finished, no_recipe for an item that is no ore, no_ore where it has no y
    (emerald), full where it stays nearly full after storing, or the reason the kit, a pickaxe, a trip or storing failed
    with."""
    if item not in lib.dig.ORES:
        return result("no_recipe", None, {"minecraft:" + item: 1})
    if y is None:
        return result("no_ore", None, {"minecraft:" + item: 1})
    h = home()
    om = memory.get("ore_miner")
    if om is None or om.get("home") != h or om["area"] != list(area) or om["y"] != y:
        om = {"home": h, "area": list(area), "y": y, "cleared": 0, "done": []}
        memory["ore_miner"] = om
    if om["cleared"] >= chunks:
        return finished(item, chunks, h)
    r = lib.bootstrap.kit(MINER, radius)
    if not r["ok"]:
        return r
    go_home()
    lib.depot.depot(NAME, h + FACING[OPPOSITE[FACE]], turn(FACE, []), CATEGORIES)
    took = better_pickaxe(item, radius)  # before ensure_pickaxe, which would otherwise make or fetch a worse one
    while True:
        lib.food.upkeep(radius)
        r = ensure_pickaxe(item, y, radius)
        if not r["ok"]:
            return r
        r = trip(item, y, claim(area, chunks), om)
        s = unload(item, radius, took)
        took = False
        if not r["ok"] or not s["ok"]:
            return r if not r["ok"] else s
        if "dug" in r:
            return finished(item, chunks, h)
        if free_slots() < FREE:
            return result("full", None, {})


def finished(item, chunks, h):
    """Goes home and says its claim of chunks round home h is cleared. Gives result() with finished."""
    go_home()
    say(f"ore_miner {item}: finished my {chunks} chunks around {h.x} {h.y} {h.z}, move me or give me a new job")
    r = result()
    r["finished"] = True
    return r


def ensure_pickaxe(item, y, radius):
    """A pickaxe for the next trip: one that lasts the stairs and a branch (lib.get.ensure_tool)."""
    return lib.get.ensure_tool("pickaxe", USES + 3 * max(pos().y - y, 0), tier(item), radius)


def run(ore="raw_iron", chunks=9, radius=32, y=None, area=None):
    """Mines ore in a claim of chunks chunks spiralling out from the home chunk (from area, (x0, z0, x1, z1), in areas
    of its size, where given) at y (lib.dig.best_y(ore), but DEPTH below home at least, where None) and keeps it at home, warns where it fails and gives
    back what it borrowed, also when it fails. Gives ore_miner's result()."""
    item = lib.dig.short(ore)
    if y is None:
        y = lib.dig.best_y(item)
        if y is not None:
            y = min(y, home().y - DEPTH)
    if area is None:
        area = chunk(home())
    try:
        r = ore_miner(item, y, area, chunks, min(radius, MAX_RADIUS))
        if not r["ok"]:
            warn("ore miner:", r["reason"], r["at"], r["missing"])
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
    run(args[0] if args else "raw_iron", int(args[1]) if len(args) > 1 else 9, int(args[2]) if len(args) > 2 else 32)
