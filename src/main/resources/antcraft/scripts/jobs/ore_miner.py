# A job: gathers one ore and keeps it at home (#1002). Run it as /antcraft bot run jobs.ore_miner [ore] [chunks] [radius],
# such as jobs.ore_miner raw_iron: ore is a raw item of lib.dig.ORES, raw_iron by default, chunks how many chunks it may
# mine (taken, but only the home chunk is mined for now), radius how far it looks for chests and the kit, 32. It digs
# stairs from home down to lib.dig.best_y(ore), branch-mines the home chunk there in a grid of branches 3 blocks apart,
# and goes home to smelt what it mined and store it in depot "ore" whenever it is nearly full, its pickaxe is low or the
# grid is dug, which ends the job (docs/cookbook/ore-miner.md). Another script runs it with
# run(ore, chunks, radius, y, area); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.bootstrap
import lib.craft
import lib.depot
import lib.dig
import lib.find
import lib.food
import lib.get
from jobs.strip_mine import MINER, USES, tier
from lib.borrow import borrowed, give_back
from lib.dig import FACING, turn
from lib.home import go_home
from lib.result import result

NAME = "ore"  # the depot, memory["depots"]["ore"]
CATEGORIES = {"ingots": ["#c:ingots"], "ores": ["#c:raw_materials", "#c:ores"]}
# What store leaves the bot: its pickaxe, the crafting table to make the next with and the furnace it smelts in; torches
# are kept as lib.storage.KEEP_KINDS.
KEEP = ["#minecraft:pickaxes", "minecraft:crafting_table", "minecraft:furnace"]
FREE = 3  # empty slots below which it goes home: a branch fills one with cobblestone, one with ore and one with coal
SPACING = 3  # blocks between branches, so their walls uncover every block of the level
LENGTH = 16  # the longest branch: across a chunk
FACE = "north"  # the way the stairs set off from home
COAL = "minecraft:coal"
SMELTS = 8  # items one coal smelts
MAX_RADIUS = 32  # the largest radius scan and find_nearest take (BlockScan.MAX_RADIUS)
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


def chunk(p):
    """The columns of p's chunk, (x0, z0, x1, z1)."""
    x = p.x // 16 * 16
    z = p.z // 16 * 16
    return (x, z, x + 15, z + 15)


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


def where(area, om, prior):
    """lib.find.WHERE for a trip: the columns of area that prior allows, but the two behind the bottom of the stairs, so
    no branch undermines their last steps."""
    x0, z0, x1, z1 = area
    back = FACING[OPPOSITE[om["way"]]]
    b = om["bottom"]
    guard = [(b.x + back[0], b.z + back[2]), (b.x + 2 * back[0], b.z + 2 * back[2])]
    return lambda p: x0 <= p.x <= x1 and z0 <= p.z <= z1 and (p.x, p.z) not in guard and (prior is None or prior(p))


def down(y, area, om, prior):
    """Takes the bot from home to the bottom of its stairs: those it dug before, else new ones dug to y towards FACE
    within area, turning at its edge as jobs.strip_mine does, whose bottom it keeps in om. Gives result()."""
    if "bottom" in om:
        r = goto(om["bottom"])
        return result() if r else result(r.reason, om["bottom"])
    x0, z0, x1, z1 = area
    lib.find.WHERE = lambda p: x0 <= p.x <= x1 and z0 <= p.z <= z1 and (prior is None or prior(p))
    way = FACE
    tried = []
    r = lib.dig.stairs_to(y, way)
    while r["reason"] == "outside" and way is not None:
        tried.append(way)
        way = turn(way, tried)
        r = lib.dig.stairs_to(y, way) if way is not None else r
    if r["ok"]:
        om["bottom"] = pos()
        om["way"] = way
    return r


def trip(item, y, area, om):
    """Goes down and digs the branches of the grid not done yet, each from the corridor at its column, until it is
    nearly full (FREE) or its pickaxe would not last a branch (USES), then walks home. Each branch is marked done in
    om["done"] once dug to the edge of area. Gives result(), with dug where every branch is done."""
    prior = lib.find.WHERE
    try:
        r = down(y, area, om, prior)
        if not r["ok"]:
            return r
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
    finally:
        lib.find.WHERE = prior
        go_home()


def unload(item, radius):
    """Smelts the item the bot carries at home (lib.craft.smelt_here), with coal it gets (lib.get) where it carries too
    little, then stores all but KEEP in depot NAME. Gives store's result()."""
    n = count(item)
    if n:
        fuel = -(-n // SMELTS)
        if count(COAL) < fuel:
            lib.get.get(COAL, fuel, radius)  # ponytail: where it fails, smelt_here burns any charcoal, logs or planks carried
        r = lib.craft.smelt_here(item, n)
        if not r["ok"]:
            warn("ore miner: could not smelt", item + ":", r["reason"], r["missing"])
    s = lib.depot.store(NAME, KEEP, radius)
    if not s["ok"]:
        warn("ore miner: no chest has room for", sorted(s["missing"].keys()), "and none to get:", s["reason"])
    return s


def ore_miner(item, y, area, radius):
    """Gets the kit, then makes trips from home until the grid of area at y is dug, unloading after each. Gives result():
    ok, no_recipe for an item that is no ore, no_ore where it has no y (emerald), full where it stays nearly full after
    storing, or the reason the kit, a pickaxe, a trip or storing failed with."""
    if item not in lib.dig.ORES:
        return result("no_recipe", None, {"minecraft:" + item: 1})
    if y is None:
        return result("no_ore", None, {"minecraft:" + item: 1})
    r = lib.bootstrap.kit(MINER, radius)
    if not r["ok"]:
        return r
    om = memory.get("ore_miner")
    if om is None or om["area"] != list(area) or om["y"] != y:
        om = {"area": list(area), "y": y, "done": []}
        memory["ore_miner"] = om
    go_home()
    h = home()
    lib.depot.depot(NAME, h + FACING[OPPOSITE[FACE]], turn(FACE, []), CATEGORIES)
    while True:
        lib.food.upkeep(radius)
        r = ensure_pickaxe(item, y, radius)
        if not r["ok"]:
            return r
        r = trip(item, y, area, om)
        s = unload(item, radius)
        if not r["ok"] or not s["ok"]:
            return r if not r["ok"] else s
        if "dug" in r:
            return r
        if free_slots() < FREE:
            return result("full", None, {})


def ensure_pickaxe(item, y, radius):
    """A pickaxe for the next trip: one that lasts the stairs and a branch (lib.get.ensure_tool)."""
    return lib.get.ensure_tool("pickaxe", USES + 3 * max(pos().y - y, 0), tier(item), radius)


def run(ore="raw_iron", chunks=1, radius=32, y=None, area=None):
    """Mines ore in the home chunk (area, (x0, z0, x1, z1), where given) at y (lib.dig.best_y(ore) where None) and keeps
    it at home, says how it went and gives back what it borrowed, also when it fails. Gives ore_miner's result().
    ponytail: chunks is taken for the claim of more chunks (#1003); only the home chunk is mined."""
    item = lib.dig.short(ore)
    if y is None:
        y = lib.dig.best_y(item)
    if area is None:
        area = chunk(home())
    try:
        r = ore_miner(item, y, area, min(radius, MAX_RADIUS))
        if r["ok"]:
            print("ore miner: dug the chunk")
        else:
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
    run(args[0] if args else "raw_iron", int(args[1]) if len(args) > 1 else 1, int(args[2]) if len(args) > 2 else 32)
