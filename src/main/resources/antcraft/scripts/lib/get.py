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
PLANKS = "#minecraft:planks"
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
# Ticks more to get an ore's raw items where none of the ore is exposed within radius, so lib.dig.ore digs stairs down to
# lib.dig.best_y, branches and climbs back: once for the trip, however many it gets (deep). Measured (#555, DigGameTest
# aTripDownABranchAndBackUp, stone pickaxe through stone): some 48 ticks a step down, 37 a block of branch and 10 a step
# back up. With one branch of lib.dig.LENGTH (32), from sea level (64) that is about 1200 for coal (BEST_Y 96: no
# stairs), 4000 for iron (16) and 8300 for diamond (-59) (#564). Up to 4 coal (16 torches) cost less as charcoal from
# logs (wood_for_coal), so a bot with no coal ore in sight burns wood for them.
STEP_DOWN = 48
BRANCH = 37
STEP_UP = 10
# The trip of an ore with no best_y, such as one a script adds. At 1000 the bootstrap tests dug for coal and failed.
DEEP = 2000
# The items being got, outermost first: a tool whose recipe needs one of them is not made for it.
GETTING = []


class Source:
    """How get gathers a raw item: do(n, radius) gets n more of it within radius and gives result(), with a tool of
    kind tool held (None: no tool) of at least tier ("hand": the bare hand drops it too, so a tool is made only where it
    pays; None: any tier); estimate is the ticks one takes, for options; ore the block or tag it digs, for cost, and y
    the height it digs down to for it (lib.dig.best_y; None: DEEP); near the block or tag do gets it from, where none
    within radius means there is nothing to get (None: not known)."""

    def __init__(self, do, tool=None, tier=None, estimate=None, ore=None, near=None, y=None):
        self.do = do
        self.y = y
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
        """(ticks, tier) to get n: estimate each, and deep(y) once more where none of ore is exposed within radius (one
        flight of stairs reaches a vein); the tier of a pickaxe source is that of the nearest exposed ore block
        (lib.tools.tier_for its Pos), so a modded ore in a needs_*_tool tag gets the tier it needs, else self.tier."""
        p = None if self.ore is None else lib.find.exposed(self.ore, radius)
        ticks = self.estimate * n + (deep(self.y) if self.ore is not None and p is None else 0)
        return ticks, lib.tools.tier_for(p) if p is not None and self.tool == "pickaxe" else self.tier


def deep(y):
    """The ticks of lib.dig.ore's trip from the bot's height down to y, one branch of lib.dig.LENGTH and back up; DEEP
    where y is None. ponytail: one branch, from the bot's feet; a vein found sooner or a second branch is not costed."""
    if y is None:
        return DEEP
    down = max(0, pos().y - y)
    return (STEP_DOWN + STEP_UP) * down + BRANCH * lib.dig.LENGTH


def dig_up(what):
    """A Source's do for a block that drops itself, such as dirt: mines the exposed ones within radius, nearest first."""
    return breaking(what, what, "no_block")


def breaking(item, what, reason):
    """A Source's do that mines the exposed blocks what selects within radius, nearest first, until the bot carries n more
    of item, which they drop; fails reason once none is left."""
    def do(n, radius):
        target = count(item) + n
        skip = {}
        while count(item) < target:
            p = lib.find.exposed(what, radius, skip)
            if p is None:
                return result(reason, None, {item: target - count(item)})
            skip[p] = True
            r = mine(p)
            if not r and r.reason == "no_space":
                return result("no_space", p)
        return result()
    return do


def ore(item, block, estimate):
    """The Source of a raw item lib.dig.ore digs, with a pickaxe that drops block."""
    return Source(lambda n, radius: lib.dig.ore(item, n, radius), "pickaxe", lib.tools.tier_for(block), estimate,
                  lib.dig.ORES[item], y=lib.dig.best_y(item))


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
# Saplings and apples break from leaves by hand, one in chance leaves broken (the 26.3 loot tables, no fortune); LEAF
# ticks a leaves block (hardness 0.2: 6 by hand) and the step to the next. The mangrove propagule drops from no leaves.
# ponytail: mine picks the best tool, so a bot carrying shears breaks leaves into leaves and gets none.
LEAF = 6 + lib.cost.WALK
for item, leaves, chance in [("oak_sapling", ["oak_leaves"], 20), ("spruce_sapling", ["spruce_leaves"], 20),
                             ("birch_sapling", ["birch_leaves"], 20), ("jungle_sapling", ["jungle_leaves"], 40),
                             ("acacia_sapling", ["acacia_leaves"], 20), ("dark_oak_sapling", ["dark_oak_leaves"], 20),
                             ("pale_oak_sapling", ["pale_oak_leaves"], 20), ("cherry_sapling", ["cherry_leaves"], 20),
                             ("azalea", ["azalea_leaves"], 20), ("flowering_azalea", ["flowering_azalea_leaves"], 20),
                             ("poplar_sapling", ["orange_poplar_leaves", "red_poplar_leaves", "yellow_poplar_leaves"], 20),
                             ("apple", ["oak_leaves", "dark_oak_leaves"], 200)]:
    SOURCES["minecraft:" + item] = Source(breaking("minecraft:" + item, leaves, "no_tree"), estimate=LEAF * chance,
                                          near=leaves)


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
    tried = []
    for _ in range(ROUNDS):
        p = lib.craft.plan(item, n)
        while p["gather"] and intermediate(p, item, chests, tried):
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
            # The charcoal now, before get takes the chests' coal (#564): the plan sees only what the bot carries, so with
            # the logs and no coal it would smelt charcoal for all of it and ask for more logs, and with the coal carried
            # it would burn the coal, its best fuel, to smelt the charcoal (#577). logs * 3 // 5 is the charcoal those
            # logs make (the inverse of charcoal's).
            lib.craft.craft_here("minecraft:charcoal", count("minecraft:charcoal") + logs * 3 // 5)
            continue
        for raw, k in p["gather"].items():
            r = get(raw, count(key(raw)) + k, radius)
            if not r["ok"]:
                return r
    return lib.craft.craft_here(item, n)


def intermediate(p, item, chests, tried):
    """Takes one step's item of p, a plan for item, from chests, the last step first (sticks before the planks they are
    made of): as many as the step makes, borrowed (lib.stock.take_from). Whether the bot carries more of it (#560). For
    a step of planks, where the chests hold none of the exact kind, any planks, once per step in tried: the plan made
    again uses them where the recipe takes any planks (#580). ponytail: where it takes only the exact kind (an oak door),
    the other planks stay borrowed, at most one step's worth."""
    for step, k, how in reversed(p["crafts"]):
        if step == item:
            continue
        for what in alike(step):
            if what == PLANKS:
                if step in tried:
                    continue
                tried.append(step)
            before = count(what)
            lib.stock.take_up_to(what, before + k, chests=chests)
            if count(what) > before:
                return True
    return False


def alike(step):
    """step, and any planks (PLANKS) after it for a step of planks: the plan picks oak planks where a recipe takes any."""
    return [step, PLANKS] if step.endswith("_planks") else [step]


def wood_for_coal(k, radius):
    """The logs to get instead of the k coal less what the chests of lib.stock within radius hold (#564), where they cost
    less than the coal source: that many to smelt into charcoal and two thirds as many burnt doing it, which as planks
    also burn as long as that much coal. 0 where the coal is cheaper or the chests hold all k."""
    if k:
        k -= chest_coal(radius)[1]
    if k <= 0:
        return 0
    logs, wood = charcoal(k, radius)
    return logs if wood < SOURCES[COAL].cost(k, radius)[0] else 0


def chest_coal(radius):
    """(chests, n): the chests of lib.stock within radius that hold coal, nearest first, and how much they hold."""
    chests = lib.stock.where(COAL, radius)
    return chests, sum([s.count for c in chests for s in container(c) or [] if matches(s, COAL)])


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


def make_cost(item, n, radius=32, tools=None, limit=None):
    """The estimated ticks to make n of item in total from what the bot carries: the walk to each step of the plan the
    chests of lib.stock within radius hold, as get takes them, and the raw items those cover left out (stored); the cost
    of each other raw item lib.craft.plan lists from its source (coal the walk to the nearest chest of lib.stock within
    radius that holds some, for as much as the chests hold, the rest from its source or from logs as charcoal where that
    is cheaper), SMELT for each item smelted, the walk to or the mining back of its crafting table and furnace (stations),
    and for each kind of tool their sources need the tool_cost of the highest
    tier any needs; None where a raw item has no source, or it or that tool is being got or costed. tools holds the tool
    costs found so far, by tool id, for one options, and under "chests" the chests of lib.stock, scanned once. With
    limit, None as soon as the cost is sure to be over it: a source costs at least its estimate each, so the ore scans
    and tool costs of a dear one are left out (#582)."""
    if key(item) in GETTING:
        return None
    tools = {} if tools is None else tools
    GETTING.append(key(item))
    try:
        p = lib.craft.plan(item, n)
        ticks = sum([lib.cost.SMELT * k for step, k, how in p["crafts"] if how == "smelt"]) + stations(p)
        gather = dict(p["gather"])
        if gather:
            ticks += stored(p, item, gather, radius, tools)
        need = {}
        for raw, k in gather.items():
            if k <= 0:
                continue
            s = SOURCES.get(key(raw))
            if s is None or key(raw) in GETTING:
                return None
            # Coal may come from a chest or as charcoal for less than its estimate.
            if limit is not None and ticks + (0 if key(raw) == COAL else s.estimate * k) > limit:
                return None
            if key(raw) == COAL:
                # get takes coal from the chests first: the walk to the nearest, for as much as they hold (#555).
                chests, held = chest_coal(radius)
                if chests:
                    ticks += lib.cost.walk(pos(), chests[0])
                    k -= held
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
        if limit is not None and ticks > limit:
            return None
        for kind, (t, uses) in need.items():
            c = tool_cost(kind, t, uses, radius, tools)
            if c is None:
                return None
            ticks += c
        return ticks
    finally:
        GETTING.pop()


def stations(p):
    """The ticks the stations of p, a plan, add (#630): for its crafting table and its furnace (or smoker or blast furnace)
    each, the walk to the one lib.craft.plan counts within lib.craft.REACH, as craft_here walks to one craft or smelt does
    not see, else the ticks to mine back the one craft_here puts down, with the best axe or pickaxe carried (a furnace at
    least with a wooden pickaxe, which get gets before it smelts); what makes one is in p already. ponytail: a table breaks as a log and a furnace as an ore (hardness 2.5 and 3.5 against 2 and 3),
    and a furnace counts once, though smelt_here puts one down for each stack."""
    ticks = 0
    if p["table"]:
        ticks += station(find_nearest("crafting_table", radius=lib.craft.REACH), "axe", "wood", None)
    inputs = lib.craft.smelt_inputs(p)
    if inputs:
        ticks += station(lib.craft.furnace_at(inputs), "pickaxe", "ore", "wooden")
    return ticks


def station(at, kind, material, least):
    """The walk to at, a station's Pos; where it is None, the ticks to break one of material with the best tool of kind
    carried, else with one of tier least (None: the hand)."""
    if at is not None:
        return lib.cost.walk(pos(), at)
    tool = lib.tools.best(kind)
    return lib.cost.breaking(1, material, least if tool is None else lib.tools.tier(tool))


def stored(p, item, gather, radius, tools):
    """The walk to the nearest chest of lib.stock within radius that holds each step of p, a plan for item, as
    intermediate takes them (planks of any kind for planks), lowering gather by the raw items the steps they cover take
    (#580); tools as make_cost's. ponytail: needs of the step alone, so a carried item the plan used for another step
    may count twice."""
    ticks = 0
    for step, k, how in reversed(p["crafts"]):
        if step == item:
            continue
        if "chests" not in tools:
            tools["chests"] = lib.stock.chests(radius)
        chests = tools["chests"]
        for what in alike(step):
            have = [c for c in chests if lib.stock.holds(c, what)]
            held = min(k, sum([s.count for c in have for s in container(c) or [] if matches(s, what)]))
            if held <= 0:
                continue
            ticks += lib.cost.walk(pos(), have[0])
            left = needs(step, k - held)["gather"] if held < k else {}
            for raw, m in needs(step, k)["gather"].items():
                gather[raw] = gather.get(raw, 0) - m + left.get(raw, 0)
            k -= held
    return ticks


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
    make_cost(tool, n, radius). A chest tool of no listed tier (None, only where tier is None or "hand") breaks as a
    wooden one. ponytail: the uses of carried tools of another tier do not count toward making one."""
    ok, carried, need = lasting(kind, uses, tier)
    found = []
    if tier == "hand":
        found.append((work(kind, uses, None), "hand", None, None))
    if carried and need <= 0:
        found.append((work(kind, uses, lib.tools.tier(carried[0])), "carried", lib.tools.tier(carried[0]), None))
    for chest, t in in_chests(kind, ok, need, radius):
        found.append((lib.cost.walk(pos(), chest) + work(kind, uses, t or "wooden"), "chest", t, chest))
    # The hand and the carried tools never fail, so ensure_tool tries nothing after them: a tier dearer to make is left
    # out, told by its raw items' estimates before their ore scans (a diamond axe for a few logs, #582).
    sure = min([o[0] for o in found if o[1] in ("hand", "carried")] or [None])
    costs = {}
    # Lowest tier first, each up to the cheapest make so far (#595): a dearer tier would only follow it in the list.
    # ponytail: a dearer tier is then no fallback where the cheaper one fails; the lowest one, costed first, always is.
    limit = sure
    for t in reversed(lib.craft.MADE):
        if t in ok:
            m = make(kind, t, uses, radius, costs, limit)
            if m is not None:
                found.append((m[0], "make", t, m[1]))
                limit = m[0] if limit is None else min(limit, m[0])
    return sorted(found, key=lambda o: o[0])


def work(kind, uses, tier):
    """cost.breaking of uses blocks of the MATERIAL of kind with a tool of tier; 0 for a kind that breaks none."""
    material = MATERIAL.get(kind)
    return lib.cost.breaking(uses, material, tier) if material else 0


def make(kind, t, uses, radius, costs, limit=None):
    """(ticks, n) to make tools of kind and tier t for uses blocks, n how many to carry in all: make_cost and the work;
    None where they cannot be made, are being got, or cost more than limit (None: no limit; see make_cost)."""
    tool = f"minecraft:{t}_{kind}"
    if tool in GETTING:
        return None
    have = sum([lib.tools.uses_left(s) for s in inventory() if s.item == tool])
    n = count(tool) + -(-max(uses - have, 1) // lib.cost.USES[t])
    ticks = make_cost(tool, n, radius, costs, None if limit is None else limit - work(kind, uses, t))
    return None if ticks is None else (ticks + work(kind, uses, t), n)


def lasting(kind, uses, tier):
    """(ok, carried, need): the tiers in ok are at least tier (all for None and "hand"), carried the tools of kind of
    those the bot carries, best first, and need the uses they leave to the rest (a worn tool is used up first). For None
    and "hand" carried counts a tool of no listed tier too, such as a modded one (#562), and ok holds None for one in a
    chest (#580)."""
    tiers = lib.tools.TIERS
    any_tier = tier in (None, "hand")
    ok = [t for t in tiers if any_tier or tiers.index(t) <= tiers.index(tier)] + ([None] if any_tier else [])
    carried = [s for s in lib.tools.carried(kind) if any_tier or lib.tools.tier(s) in ok]
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
    Carried ones that last, else (tier not "hand") one that lasts in the nearest chest where the walk there and the work
    with it cost no more than making the lowest tier of lib.craft.MADE it may (#580), it takes before costing the
    options, which scans for each tier it could make; where no tool can be made for want of a raw item (no_wood), it
    fails at once with that source's reason, as no_logs.
    Gives result(): ok, no_tool where there is no way, or the reason the last tier to make failed with."""
    ok, carried, need = lasting(kind, uses, tier)
    if carried and need <= 0:
        return lib.tools.hold(kind)
    if tier != "hand":
        # The core and memory["chests"] first, which need no scan: a scan of the sphere waits on a budget all bots share.
        # ponytail: the lowest tier's make cost for the first chest tool past the bound, not options, so a higher tier
        # made that beats it, or a farther chest tool, is left to options.
        low = [m for m in lib.craft.MADE if m in ok]
        weighed = False
        for scanning in (False, True):
            for chest, t in in_chests(kind, ok, need, radius, scanning):
                c = lib.cost.walk(pos(), chest) + work(kind, uses, t or "wooden")
                # The work of the lowest tier alone is a bound on making it: below it, no make cost is needed.
                if low and c > work(kind, uses, low[-1]):
                    if weighed:
                        break
                    weighed = True
                    # Up to c only: None where making costs more or cannot be done (#582's limit). With the chests of
                    # this pass, so the one that needs no scan scans none for stored either.
                    m = make(kind, low[-1], uses, radius, {"chests": lib.stock.chests(radius, scanning)}, c)
                    if m is not None and m[0] < c:
                        break
                if take(chest, kind, t):
                    return lib.tools.hold(kind)
        r = no_wood(kind, ok, radius)
        if r is not None:
            return r
    failed = None
    for ticks, how, t, at in options(kind, uses, tier, radius):
        if how == "hand":
            return result()
        if how == "carried":
            return lib.tools.hold(kind)
        if how == "chest":
            if take(at, kind, t):
                return lib.tools.hold(kind)
        else:
            r = get(f"{t}_{kind}", at, radius)
            if r["ok"]:
                return lib.tools.hold(kind)
            failed = r
    return failed or result("no_tool", None, {f"{tier if tier not in (None, 'hand') else 'wooden'}_{kind}": 1})


def no_wood(kind, ok, radius):
    """The failure of the source of a raw item where no tool of kind of a tier of lib.craft.MADE in ok can be made for
    want of it (short), so ensure_tool need not cost options (#562); else None. Every tool takes sticks, so where a
    stick is short that is all; where the sticks are covered, as carried, each tier's tool is checked (#580)."""
    seen = {}
    failed = short(lib.craft.plan("minecraft:stick", 1), seen, radius)
    if failed is None:
        pickaxe = []
        for t in lib.craft.MADE:
            if t in ok:
                f = short(needs(f"minecraft:{t}_{kind}"), seen, radius, pickaxe)
                if f is None:
                    return None
                failed = failed or f
    return None if failed is None else failed[0].do(failed[1], radius)


def short(p, seen, radius, pickaxe=None):
    """(source, k) for the first raw item p, a plan, gathers, k of it, whose source has none within radius
    (Source.there) and that no chest holds, nor a step on the way (held); else, with pickaxe (a list that keeps the
    answer for one no_wood), the failure of the wooden pickaxe where a raw item no chest holds takes a pickaxe the bot
    cannot have: it carries none and no chest holds one; else None. ponytail: a tier's tool is checked by needs,
    without the crafting table plan adds."""
    steps = [w for step, n, how in p["crafts"] for w in alike(step)]
    dug = []
    for raw, k in p["gather"].items():
        s = SOURCES.get(key(raw))
        if s is None:
            continue
        if s.there(radius):
            if s.tool == "pickaxe":
                dug.append(raw)
        elif not held(raw, steps, seen, radius):
            return s, k
    if pickaxe is None or not [raw for raw in dug if not held(raw, steps, seen, radius)]:
        return None
    if not pickaxe:
        has = count("#minecraft:pickaxes") or in_chests("pickaxe", lib.tools.TIERS + [None], 1, radius)
        pickaxe.append(None if has else short(needs("minecraft:wooden_pickaxe"), seen, radius))
    return pickaxe[0]


def held(raw, steps, seen, radius):
    """Whether a chest of lib.stock within radius, scanned once into seen["chests"], holds raw or any of steps.
    ponytail: any of it covers it."""
    if "chests" not in seen:
        seen["chests"] = lib.stock.chests(radius)
    return any([lib.stock.holds(c, w) for c in seen["chests"] for w in [key(raw)] + steps])


def take(chest, kind, t):
    """Whether lib.stock.take_from took one tool of kind and tier t (None: of no listed tier) from chest."""
    tools = [s.item for s in container(chest) or [] if matches(s, f"#minecraft:{kind}s") and lib.tools.tier(s) == t]
    if not tools:
        return False
    before = count(tools[0])
    lib.stock.take_from(chest, tools[0], 1)
    return count(tools[0]) > before
