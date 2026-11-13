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
COAL = "minecraft:coal"
# How often get plans and gets the raw items again before it crafts with what it has.
ROUNDS = 5
# How many gets may wait on each other, the outermost included, before get gives up with cycle.
DEPTH = 8
# The material a tool of each kind breaks, for cost.breaking; a kind not listed breaks nothing.
MATERIAL = {"pickaxe": "stone", "axe": "wood", "shovel": "dirt"}
# Ticks to get one of a raw item, where its Source gives no estimate.
ESTIMATE = 100
# Ticks to get one of an ore's raw item where none of the ore is exposed within radius, so lib.dig digs stairs for it.
# ponytail: one guess for every ore and depth; the length of the stairs if the choices it sways prove wrong.
DEEP = 1000
# The items being got, outermost first: a tool whose recipe needs one of them is not made for it.
GETTING = []


class Source:
    """How get gathers a raw item: do(n, radius) gets n more of it within radius and gives result(), with a tool of
    kind tool held (None: no tool) of at least tier ("hand": the bare hand drops it too, so a tool is made only where it
    pays; None: any tier); estimate is the ticks one takes, for options; ore the block or tag it digs, for ticks."""

    def __init__(self, do, tool=None, tier=None, estimate=None, ore=None):
        self.do = do
        self.tool = tool
        self.tier = tier
        self.estimate = ESTIMATE if estimate is None else estimate
        self.ore = ore

    def ticks(self, radius):
        """estimate, or DEEP where none of ore is exposed within radius."""
        if self.ore is not None and lib.find.exposed(self.ore, radius) is None:
            return DEEP
        return self.estimate


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
    return Source(lambda n, radius: lib.dig.ore(item, n, radius), "pickaxe", lib.tools.tier_for(block), estimate,
                  lib.dig.ORES[item])


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
    borrowed, as many as they hold, the rest by what follows; SOURCES[item], with ensure_tool first; crafting, getting each raw item lib.craft.plan lists, then
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
    if lib.stock.take_up_to(item, n, radius)["ok"]:
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
        logs = wood_for_coal(p["gather"].get(COAL, 0), radius)
        if logs and get(LOGS, count(LOGS) + logs, radius)["ok"]:
            continue
        for raw, k in p["gather"].items():
            r = get(raw, count(key(raw)) + k, radius)
            if not r["ok"]:
                return r
    return lib.craft.craft_here(item, n)


def wood_for_coal(k, radius):
    """The logs to get instead of k coal, where they cost less than the coal source and no chest within radius holds
    coal: k to smelt into charcoal and two thirds as many burnt doing it, which as planks also burn as long as k coal.
    0 where the coal is cheaper."""
    logs = k + -(-2 * k // 3)
    if not k or lib.stock.where(COAL, radius):
        return 0
    wood = SOURCES[LOGS].ticks(radius) * logs + lib.cost.SMELT * k
    return logs if wood < SOURCES[COAL].ticks(radius) * k else 0


def gather(source, n, radius):
    """source.do(n, radius) with its tool held: n more of its item. For an ore source the pickaxe tier is that of the
    nearest exposed ore block (lib.tools.tier_for its Pos), so a modded ore in a needs_*_tool tag gets the tier it needs.
    ponytail: only the first block's tier; a later vein that needs more is tried with this pickaxe."""
    tier = source.tier
    if source.tool == "pickaxe" and source.ore is not None:
        p = lib.find.exposed(source.ore, radius)
        if p is not None:
            tier = lib.tools.tier_for(p)
    if source.tool:
        r = ensure_tool(source.tool, n, tier, radius)
        if not r["ok"]:
            return r
    return source.do(n, radius)


def make_cost(item, n, radius=32, tools=None):
    """The estimated ticks to make n of item in total from what the bot carries: the ticks(radius) of each raw item
    lib.craft.plan lists, SMELT for each item smelted, and for each kind of tool their sources need the make cost of
    the highest tier any needs where the bot carries none that lasts them all; None where a raw item has no source, or
    it or that tool is being got or costed. tools holds the tool costs found so far, by tool id, for one options."""
    if key(item) in GETTING:
        return None
    GETTING.append(key(item))
    try:
        p = lib.craft.plan(item, n)
        ticks = sum([lib.cost.SMELT * k for step, k, how in p["crafts"] if how == "smelt"])
        need = {}
        for raw, k in p["gather"].items():
            s = SOURCES.get(key(raw))
            if s is None or key(raw) in GETTING:
                return None
            ticks += s.ticks(radius) * k
            if s.tool and s.tier != "hand":
                t, uses = need.get(s.tool, ("wooden", 0))
                need[s.tool] = (min([t, s.tier or "wooden"], key=lib.tools.TIERS.index), uses + k)
        for kind, (t, uses) in need.items():
            c = tool_cost(kind, t, uses, radius, {} if tools is None else tools)
            if c is None:
                return None
            ticks += c
        return ticks
    finally:
        GETTING.pop()


def tool_cost(kind, tier, uses, radius, tools):
    """0 where the bot carries tools of kind of at least tier that last uses, else make_cost of one of tier.
    ponytail: one tool, made; a chest's is not looked for."""
    ok = lib.tools.TIERS[:lib.tools.TIERS.index(tier) + 1]
    if sum([lib.tools.uses_left(s) for s in lib.tools.carried(kind) if lib.tools.tier(s) in ok]) >= uses:
        return 0
    tool = f"minecraft:{tier}_{kind}"
    if tool not in tools:
        tools[tool] = make_cost(tool, 1, radius, tools)
    return tools[tool]


def options(kind, uses=1, tier=None, radius=32):
    """The ways ensure_tool can give a tool of kind for uses blocks of at least tier, cheapest first, each a tuple
    (ticks, how, tier, at): ("hand", None) where tier is "hand"; ("carried", its tier) where the carried ones last;
    ("chest", its tier) with at the chest, for a chest tool that lasts with the carried ones; ("make", a tier of
    lib.craft.MADE) with at how many to carry in all. ticks is cost.breaking for the uses, plus the walk to a chest or
    make_cost(tool, n, radius). ponytail: the uses of carried tools of another tier do not count toward making one."""
    material = MATERIAL.get(kind)
    tiers = lib.tools.TIERS
    ok = [t for t in tiers if tier in (None, "hand") or tiers.index(t) <= tiers.index(tier)]

    def work(t):
        return lib.cost.breaking(uses, material, t) if material else 0

    found = []
    if tier == "hand":
        found.append((work(None), "hand", None, None))
    carried = [s for s in lib.tools.carried(kind) if lib.tools.tier(s) in ok]
    # The uses the carried tools leave to the rest: a worn tool is used up first.
    need = uses - sum([lib.tools.uses_left(s) for s in carried])
    if carried and need <= 0:
        found.append((work(lib.tools.tier(carried[0])), "carried", lib.tools.tier(carried[0]), None))
    for chest in lib.stock.chests(radius):
        for s in container(chest) or []:
            t = lib.tools.tier(s)
            if t in ok and matches(s, f"#minecraft:{kind}s") and lib.tools.uses_left(s) >= need:
                found.append((lib.cost.walk(pos(), chest) + work(t), "chest", t, chest))
    costs = {}
    for t in lib.craft.MADE:
        tool = f"minecraft:{t}_{kind}"
        if t not in ok or tool in GETTING:
            continue
        have = sum([lib.tools.uses_left(s) for s in inventory() if s.item == tool])
        n = count(tool) + -(-max(uses - have, 1) // lib.cost.USES[t])
        ticks = make_cost(tool, n, radius, costs)
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
    of options: carried, borrowed from a chest of lib.stock, or made with get; where one fails it tries the next.
    Gives result(): ok, no_tool where there is no way, or the reason the last tier to make failed with."""
    failed = None
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
            if r["ok"]:
                return lib.tools.hold(kind)
            failed = r
    return failed or result("no_tool", None, {f"{tier if tier not in (None, 'hand') else 'wooden'}_{kind}": 1})
