# Getting anything, for any script: `from lib.get import get, ensure_tool`, then get("stone_axe").
# A module of the mod's default library (lib/), the resolver: it imports the skills and primitives below it and no skill
# imports it. It satisfies what the skills assume: the tool a source needs and the raw items a recipe needs
# (docs/design/library.md, The resolver).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.borrow
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
# Ticks more to get an ore's raw items where none of the ore is exposed within radius, so lib.dig.ore digs stairs down,
# branches and climbs back: once for the trip, however many it gets. Up to 7 coal (28 torches) cost less as charcoal
# from logs (wood_for_coal), so a bot with no coal ore in sight burns wood for them.
# Measured (#555, DigGameTest aTripDownABranchAndBackUp, stone pickaxe through stone): some 48 ticks a step down, 37 a
# block of branch and 10 a step back up. From sea level (64) with one branch of lib.dig.LENGTH (32) that is about 1500
# for coal (BEST_Y 96: no stairs), 4300 for iron (16) and 8600 for diamond (-59).
# ponytail: one number for every ore, between coal's trip and iron's; per ore from its BEST_Y and the bot's height if the
# choices it sways prove wrong. At 1000 the bootstrap tests dug for coal and failed.
DEEP = 2000
# The items being got, outermost first: a tool whose recipe needs one of them is not made for it.
GETTING = []


class Source:
    """How get gathers a raw item: do(n, radius) gets n more of it within radius and gives result(), with a tool of
    kind tool held (None: no tool) of at least tier ("hand": the bare hand drops it too, so a tool is made only where it
    pays; None: any tier); estimate is the ticks one takes, for options; ore the block or tag it digs, for cost; near
    the block or tag do gets it from, where none within radius means there is nothing to get (None: not known)."""

    def __init__(self, do, tool=None, tier=None, estimate=None, ore=None, near=None):
        self.do = do
        self.tool = tool
        self.tier = tier
        self.estimate = ESTIMATE if estimate is None else estimate
        self.ore = ore
        self.near = near

    def there(self, radius):
        """Whether do may find something within radius: False where near is set and none of it is (#554), so get gives
        up at once instead of costing the tools to gather nothing with. make_cost still costs it, as if there were some."""
        return self.near is None or lib.find.nearest(self.near, radius) is not None

    def cost(self, n, radius):
        """(ticks, tier) to get n: estimate each, and DEEP once more where none of ore is exposed within radius (one
        flight of stairs reaches a vein); the tier of a pickaxe source is that of the nearest exposed ore block
        (lib.tools.tier_for its Pos), so a modded ore in a needs_*_tool tag gets the tier it needs, else self.tier."""
        p = None if self.ore is None else lib.find.exposed(self.ore, radius)
        ticks = self.estimate * n + (DEEP if self.ore is not None and p is None else 0)
        return ticks, lib.tools.tier_for(p) if p is not None and self.tool == "pickaxe" else self.tier


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
    LOGS: Source(lambda n, radius: lib.trees.logs(n, radius), "axe", "hand", lib.cost.BREAK["wood"]["stone"] + 8 * lib.cost.WALK,
                 near=LOGS),
    COBBLESTONE: Source(lib.dig.stone, "pickaxe", None, lib.cost.BREAK["stone"]["wooden"] + 2 * lib.cost.WALK),
    "minecraft:dirt": Source(dig_up("minecraft:dirt"), "shovel", "hand", lib.cost.BREAK["dirt"]["hand"] + 2 * lib.cost.WALK,
                             near="minecraft:dirt"),
    "minecraft:sand": Source(dig_up("minecraft:sand"), "shovel", "hand", lib.cost.BREAK["dirt"]["hand"] + 2 * lib.cost.WALK,
                             near="minecraft:sand"),
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
    borrowed, as many as they hold, the rest by what follows; SOURCES[item], with ensure_tool first; crafting, taking
    what the plan crafts on the way from those chests (intermediate), then getting each raw item lib.craft.plan still
    lists, then lib.craft.craft_here. Gives result(): ok, no_recipe with missing for an item
    it can neither gather nor craft, cycle where getting it waits on itself, or the reason a step failed with. Where it
    fails it gives back what it borrowed during this call (lib.borrow.give_back), not what was borrowed before it."""
    item = key(item)
    if count(item) >= n:
        return result()
    if item in GETTING or len(GETTING) >= DEPTH:
        return result("cycle", None, {item: n - count(item)})
    GETTING.append(item)
    # What this call borrows is given back when it fails; a nested get that failed gave its own back already.
    since = len(lib.borrow.borrowed())
    try:
        r = resolve(item, n, radius)
    finally:
        GETTING.pop()
    if not r["ok"]:
        lib.borrow.give_back(since=since)
    return r


def resolve(item, n, radius):
    # One list of chests for the item and its intermediates: a scan waits on a budget all bots share.
    chests = lib.stock.chests(radius)
    if lib.stock.take_up_to(item, n, chests=chests)["ok"]:
        return result()
    s = SOURCES.get(item)
    if s is not None:
        return gather(s, n - count(item), radius)
    if item.startswith("#"):
        return result("no_recipe", None, {item: n - count(item)})
    for _ in range(ROUNDS):
        p = lib.craft.plan(item, n)
        while p["gather"] and intermediate(p, item, chests):
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


def intermediate(p, item, chests):
    """Takes one step's item of p, a plan for item, from chests, the last step first (sticks before the planks they are
    made of): as many as the step makes, borrowed (lib.stock.take_from). Whether the bot carries more of it (#560).
    ponytail: the exact item of the step, so oak planks the plan picked are not birch planks in a chest."""
    for step, k, how in reversed(p["crafts"]):
        if step == item:
            continue
        before = count(step)
        lib.stock.take_up_to(step, before + k, chests=chests)
        if count(step) > before:
            return True
    return False


def wood_for_coal(k, radius):
    """The logs to get instead of k coal, where they cost less than the coal source and no chest within radius holds
    coal: k to smelt into charcoal and two thirds as many burnt doing it, which as planks also burn as long as k coal.
    0 where the coal is cheaper."""
    if not k or lib.stock.where(COAL, radius):
        return 0
    logs, wood = charcoal(k, radius)
    return logs if wood < SOURCES[COAL].cost(k, radius)[0] else 0


def charcoal(k, radius):
    """(logs, ticks): the logs that stand in for k coal (see wood_for_coal) and the ticks to get them and smelt k."""
    logs = k + -(-2 * k // 3)
    return logs, SOURCES[LOGS].cost(logs, radius)[0] + lib.cost.SMELT * k


def gather(source, n, radius):
    """source.do(n, radius) with its tool held, of the tier source.cost gives: n more of its item. Where there is nothing
    to gather (not source.there), no tool: do fails at once, with its own reason (#554). Where do gives a tier too, as
    lib.dig.ore's no_ore for the veins it left (#555), it ensures a tool of that tier and does it once more for the rest.
    ponytail: once; a vein left on the second try needs a third get."""
    if not source.there(radius):
        return source.do(n, radius)
    tier = source.cost(n, radius)[1]
    if source.tool:
        r = ensure_tool(source.tool, n, tier, radius)
        if not r["ok"]:
            return r
    r = source.do(n, radius)
    if r["ok"] or not source.tool or r.get("tier") is None:
        return r
    rest = sum(r["missing"].values())
    t = ensure_tool(source.tool, rest, r["tier"], radius)
    return source.do(rest, radius) if t["ok"] else t


def make_cost(item, n, radius=32, tools=None):
    """The estimated ticks to make n of item in total from what the bot carries: the cost of each raw item
    lib.craft.plan lists from its source (coal the walk to the nearest chest of lib.stock within radius that holds some,
    for as much as the chests hold, the rest from its source or from logs as charcoal where that is cheaper), SMELT for each
    item smelted, and for each kind of tool their sources need the tool_cost of the highest tier any needs; None where a
    raw item has no source, or it or that tool is being got or costed. tools holds the tool costs found so far, by tool
    id, for one options."""
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
            if key(raw) == COAL:
                # get takes coal from the chests first: the walk to the nearest, for as much as they hold (#555).
                chests = lib.stock.where(COAL, radius)
                if chests:
                    ticks += lib.cost.walk(pos(), chests[0])
                    k -= sum([s.count for c in chests for s in container(c) or [] if matches(s, COAL)])
                    if k <= 0:
                        continue
            c, tier = s.cost(k, radius)
            if key(raw) == COAL and LOGS not in GETTING:
                wood = charcoal(k, radius)[1]
                if wood < c:
                    ticks += wood
                    continue
            ticks += c
            if s.tool and tier != "hand":
                t, uses = need.get(s.tool, ("wooden", 0))
                need[s.tool] = (min([t, tier or "wooden"], key=lib.tools.TIERS.index), uses + k)
        for kind, (t, uses) in need.items():
            c = tool_cost(kind, t, uses, radius, {} if tools is None else tools)
            if c is None:
                return None
            ticks += c
        return ticks
    finally:
        GETTING.pop()


def tool_cost(kind, tier, uses, radius, tools):
    """0 where the bot carries tools of kind of at least tier that last uses; else the walk to the nearest chest of
    lib.stock within radius with one that lasts the rest; else make_cost of one of tier.
    ponytail: one tool, made, where no chest has one that lasts."""
    ok = lib.tools.TIERS[:lib.tools.TIERS.index(tier) + 1]
    rest = uses - sum([lib.tools.uses_left(s) for s in lib.tools.carried(kind) if lib.tools.tier(s) in ok])
    if rest <= 0:
        return 0
    found = in_chests(kind, ok, rest, radius)
    if found:
        return lib.cost.walk(pos(), found[0][0])
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
    ok, carried, need = lasting(kind, uses, tier)

    def work(t):
        return lib.cost.breaking(uses, material, t) if material else 0

    found = []
    if tier == "hand":
        found.append((work(None), "hand", None, None))
    if carried and need <= 0:
        found.append((work(lib.tools.tier(carried[0])), "carried", lib.tools.tier(carried[0]), None))
    for chest, t in in_chests(kind, ok, need, radius):
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


def lasting(kind, uses, tier):
    """(ok, carried, need): the tiers in ok are at least tier (all for None and "hand"), carried the tools of kind of
    those the bot carries, best first, and need the uses they leave to the rest (a worn tool is used up first)."""
    tiers = lib.tools.TIERS
    ok = [t for t in tiers if tier in (None, "hand") or tiers.index(t) <= tiers.index(tier)]
    carried = [s for s in lib.tools.carried(kind) if lib.tools.tier(s) in ok]
    return ok, carried, uses - sum([lib.tools.uses_left(s) for s in carried])


def in_chests(kind, ok, uses, radius, scanning=True):
    """(chest, tier) for each tool of kind of a tier in ok that lasts uses in the chests of lib.stock within radius,
    nearest chest first; without scanning only the core and memory["chests"] (lib.stock.chests)."""
    found = []
    for chest in lib.stock.chests(radius, scanning):
        for s in container(chest) or []:
            t = lib.tools.tier(s)
            if t in ok and matches(s, f"#minecraft:{kind}s") and lib.tools.uses_left(s) >= uses:
                found.append((chest, t))
    return found


def choose(kind, uses=1, tier=None, radius=32):
    """The cheapest of options(kind, uses, tier, radius), a tuple (ticks, how, tier, at); None where there is none."""
    found = options(kind, uses, tier, radius)
    return found[0] if found else None


def ensure_tool(kind, uses=1, tier=None, radius=32):
    """Makes the bot hold a tool of kind ("pickaxe", "axe", "shovel", "hoe" or "sword") that lasts uses more blocks and
    is at least tier (None: any; "hand": none needed, so a tool only where it is cheaper than the hand), by the cheapest
    of options: carried, borrowed from a chest of lib.stock, or made with get; where one fails it tries the next.
    Carried ones that last, else (tier not "hand") one that lasts in the nearest chest, it takes before costing the
    options, which scans for each tier it could make.
    Gives result(): ok, no_tool where there is no way, or the reason the last tier to make failed with."""
    ok, carried, need = lasting(kind, uses, tier)
    if carried and need <= 0:
        return lib.tools.hold(kind)
    if tier != "hand":
        # ponytail: the nearest chest's, however far within radius; making one may be cheaper (options weighs that).
        # The core and memory["chests"] first, which need no scan: a scan of the sphere waits on a budget all bots share.
        for scanning in (False, True):
            for chest, t in in_chests(kind, ok, need, radius, scanning):
                if take(chest, f"{t}_{kind}"):
                    return lib.tools.hold(kind)
    failed = None
    for ticks, how, t, at in options(kind, uses, tier, radius):
        if how == "hand":
            return result()
        if how == "carried":
            return lib.tools.hold(kind)
        if how == "chest":
            if take(at, f"{t}_{kind}"):
                return lib.tools.hold(kind)
        else:
            r = get(f"{t}_{kind}", at, radius)
            if r["ok"]:
                return lib.tools.hold(kind)
            failed = r
    return failed or result("no_tool", None, {f"{tier if tier not in (None, 'hand') else 'wooden'}_{kind}": 1})


def take(chest, tool):
    """Whether lib.stock.take_from took one tool from chest."""
    before = count(tool)
    lib.stock.take_from(chest, tool, 1)
    return count(tool) > before
