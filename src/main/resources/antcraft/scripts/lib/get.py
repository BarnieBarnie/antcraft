# Getting anything, for any script: `from lib.get import get, ensure_tool`, then get("stone_axe").
# A module of the mod's default library (lib/), the resolver: it imports the skills and primitives below it and no skill
# imports it. It satisfies what the skills assume: the tool a source needs and the raw items a recipe needs
# (docs/design/library.md, The resolver).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.cost
import lib.craft
import lib.dig
import lib.find
import lib.stock
import lib.tools
import lib.trees
from lib.result import result

LOGS = lib.trees.LOGS
COBBLESTONE = lib.dig.COBBLESTONE
# How often get plans and gets the raw items again before it crafts with what it has.
ROUNDS = 5
# How many gets may wait on each other, the outermost included, before get gives up with cycle.
DEPTH = 8
# The material a tool of each kind breaks, for cost.breaking; a kind not listed breaks nothing.
MATERIAL = {"pickaxe": "stone", "axe": "wood", "shovel": "dirt"}
# Ticks to get one of a raw item, where its Source gives no estimate.
ESTIMATE = 100
# The items being got, outermost first: a tool whose recipe needs one of them is not made for it.
GETTING = []


class Source:
    """How get gathers a raw item: do(n, radius) gets n more of it within radius and gives result(), with a tool of
    kind tool held (None: no tool) of at least tier ("hand": the bare hand drops it too, so a tool is made only where it
    pays; None: any tier); estimate is the ticks one takes, for choose."""

    def __init__(self, do, tool=None, tier=None, estimate=None):
        self.do = do
        self.tool = tool
        self.tier = tier
        self.estimate = ESTIMATE if estimate is None else estimate


def dig_up(what):
    """A Source's do for a block that drops itself, such as dirt: mines the exposed ones within radius, nearest first."""
    def do(n, radius):
        target = count(what) + n
        skip = {}
        while count(what) < target:
            p = lib.find.exposed(what, radius, skip)
            if p is None:
                return result("no_block", None, {what: target - count(what)})
            skip[p] = True
            r = mine(p)
            if not r and r.reason == "no_space":
                return result("no_space", p)
        return result()
    return do


def ore(item, block, estimate):
    """The Source of a raw item lib.dig.ore digs, with a pickaxe that drops block."""
    return Source(lambda n, radius: lib.dig.ore(item, n, radius), "pickaxe", lib.tools.tier_for(block), estimate)


# Raw items get gathers, by id ("#minecraft:logs" for any log). A script adds its own: SOURCES["minecraft:clay_ball"] =
# Source(my_clay, tool="shovel", tier="hand"). ponytail: the estimates are rough guesses of mining plus walking.
SOURCES = {
    LOGS: Source(lambda n, radius: lib.trees.logs(n, radius), "axe", "hand", lib.cost.BREAK["wood"]["stone"] + 8 * lib.cost.WALK),
    COBBLESTONE: Source(lib.dig.stone, "pickaxe", None, lib.cost.BREAK["stone"]["wooden"] + 2 * lib.cost.WALK),
    "minecraft:dirt": Source(dig_up("minecraft:dirt"), "shovel", "hand", lib.cost.BREAK["dirt"]["hand"] + 2 * lib.cost.WALK),
    "minecraft:sand": Source(dig_up("minecraft:sand"), "shovel", "hand", lib.cost.BREAK["dirt"]["hand"] + 2 * lib.cost.WALK),
}
for item, block, estimate in [("coal", "coal_ore", 40), ("raw_iron", "iron_ore", 60), ("raw_copper", "copper_ore", 60),
                              ("raw_gold", "gold_ore", 400), ("redstone", "redstone_ore", 400),
                              ("lapis_lazuli", "lapis_ore", 300), ("diamond", "diamond_ore", 2000),
                              ("emerald", "emerald_ore", 2000)]:
    SOURCES["minecraft:" + item] = ore(item, block, estimate)


def key(item):
    """item's full id, and LOGS for a log without a source of its own (any log makes planks)."""
    if item.startswith("#"):
        return item
    if ":" not in item:
        item = "minecraft:" + item
    return LOGS if item.endswith("_log") and item not in SOURCES else item


def get(item, n=1, radius=32):
    """Makes the bot carry at least n of item, by the first of: what it carries; the chests of lib.stock within radius,
    borrowed; SOURCES[item], with ensure_tool first; crafting, getting each raw item lib.craft.plan lists, then
    lib.craft.craft_here. Gives result(): ok, no_recipe with missing for an item it can neither gather nor craft, cycle
    where getting it waits on itself, or the reason a step failed with."""
    item = key(item)
    if count(item) >= n:
        return result()
    if item in GETTING or len(GETTING) >= DEPTH:
        return result("cycle", None, {item: n - count(item)})
    GETTING.append(item)
    try:
        return resolve(item, n, radius)
    finally:
        GETTING.pop()


def resolve(item, n, radius):
    if lib.stock.where(item, radius) and lib.stock.fetch(item, n, radius)["ok"]:
        return result()
    s = SOURCES.get(item)
    if s is not None:
        return gather(s, n - count(item), radius)
    if item.startswith("#"):
        return result("no_recipe", None, {item: n - count(item)})
    for _ in range(ROUNDS):
        p = lib.craft.plan(item, n)
        if item in p["gather"]:
            return result("no_recipe", None, {item: n - count(item)})
        if p["no_tool"]:
            # A furnace craft_here puts down comes back only with a pickaxe.
            r = ensure_tool("pickaxe", 1, None, radius)
            if not r["ok"]:
                return r
            continue
        if not p["gather"]:
            break
        for raw, k in p["gather"].items():
            r = get(raw, count(key(raw)) + k, radius)
            if not r["ok"]:
                return r
    return lib.craft.craft_here(item, n)


def gather(source, n, radius):
    """source.do(n, radius) with its tool held: n more of its item."""
    if source.tool:
        r = ensure_tool(source.tool, n, source.tier, radius)
        if not r["ok"]:
            return r
    return source.do(n, radius)


def make_cost(item, n):
    """The estimated ticks to make n of item in total from what the bot carries: the estimate of each raw item
    lib.craft.plan lists and SMELT for each item smelted; None where a raw item has no source or is being got."""
    p = lib.craft.plan(item, n)
    ticks = sum([lib.cost.SMELT * k for step, k, how in p["crafts"] if how == "smelt"])
    for raw, k in p["gather"].items():
        s = SOURCES.get(key(raw))
        if s is None or key(raw) in GETTING:
            return None
        ticks += s.estimate * k
    return ticks


def options(kind, uses=1, tier=None, radius=32):
    """The ways ensure_tool can give a tool of kind for uses blocks of at least tier, cheapest first, each a tuple
    (ticks, how, tier, at): ("hand", None) where tier is "hand"; ("carried", its tier) where the carried ones last;
    ("chest", its tier) with at the chest, for a chest tool that lasts on its own; ("make", a tier of lib.craft.MADE)
    with at how many to carry in all. ticks is cost.breaking for the uses, plus the walk to a chest or make_cost.
    ponytail: a carried or chest tool that does not last on its own is left out, not used up first."""
    material = MATERIAL.get(kind)
    tiers = lib.tools.TIERS
    ok = [t for t in tiers if tier in (None, "hand") or tiers.index(t) <= tiers.index(tier)]

    def work(t):
        return lib.cost.breaking(uses, material, t) if material else 0

    found = []
    if tier == "hand":
        found.append((work(None), "hand", None, None))
    carried = [s for s in lib.tools.carried(kind) if lib.tools.tier(s) in ok]
    if carried and sum([lib.tools.uses_left(s) for s in carried]) >= uses:
        found.append((work(lib.tools.tier(carried[0])), "carried", lib.tools.tier(carried[0]), None))
    for chest in lib.stock.chests(radius):
        for s in container(chest) or []:
            t = lib.tools.tier(s)
            if t in ok and matches(s, f"#minecraft:{kind}s") and lib.tools.uses_left(s) >= uses:
                found.append((lib.cost.walk(pos(), chest) + work(t), "chest", t, chest))
    for t in lib.craft.MADE:
        tool = f"minecraft:{t}_{kind}"
        if t not in ok or tool in GETTING:
            continue
        have = sum([lib.tools.uses_left(s) for s in inventory() if s.item == tool])
        n = count(tool) + -(-max(uses - have, 1) // lib.cost.USES[t])
        ticks = make_cost(tool, n)
        if ticks is not None:
            found.append((ticks + work(t), "make", t, n))
    return sorted(found, key=lambda o: o[0])


def choose(kind, uses=1, tier=None, radius=32):
    """The cheapest of options(kind, uses, tier, radius), a tuple (ticks, how, tier, at); None where there is none."""
    found = options(kind, uses, tier, radius)
    return found[0] if found else None


def ensure_tool(kind, uses=1, tier=None, radius=32):
    """Makes the bot hold a tool of kind ("pickaxe", "axe", "shovel", "hoe" or "sword") that lasts uses more blocks and
    is at least tier (None: any; "hand": none needed, so a tool only where it is cheaper than the hand), by the cheapest
    of options: carried, borrowed from a chest of lib.stock, or made with get. Only the cheapest tier to make is tried.
    Gives result(): ok, no_tool where there is no way, or the reason borrowing or get failed with."""
    for ticks, how, t, at in options(kind, uses, tier, radius):
        if how == "hand":
            return result()
        if how == "carried":
            return lib.tools.hold(kind)
        if how == "chest":
            tool = f"{t}_{kind}"
            before = count(tool)
            lib.stock.take_from(at, tool, 1)
            if count(tool) > before:
                return lib.tools.hold(kind)
        else:
            r = get(f"{t}_{kind}", at, radius)
            return lib.tools.hold(kind) if r["ok"] else r
    return result("no_tool", None, {f"{tier if tier not in (None, 'hand') else 'wooden'}_{kind}": 1})
