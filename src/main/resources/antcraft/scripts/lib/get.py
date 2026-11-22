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
LOG = "minecraft:oak_log"
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
# back up. With the branches of lib.dig.LENGTH (32) lib.dig.branches gives for one, from sea level (64) that is about
# 1200 for coal (BEST_Y 96: no stairs, one branch), 5600 for iron (16) and 12,900 for diamond (-59) (#564, #985). Up to 4 coal (16 torches) cost less as charcoal from
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
    within radius means there is nothing to get (None: not known), and keep(p) whether do would take the one at p (None:
    any, as the leaves sources take no persistent leaves, #649); drops(level) the mean items a block drops with a tool of
    that Fortune level (None: one each), so estimate is the ticks of one block then (#754); branches(blocks) how many
    branches the trip digs on average to find blocks of ore (lib.dig.branches; None: one)."""

    def __init__(self, do, tool=None, tier=None, estimate=None, ore=None, near=None, y=None, keep=None, drops=None,
                 branches=None):
        self.do = do
        self.branches = branches
        self.drops = drops
        self.keep = keep
        self.y = y
        self.tool = tool
        self.tier = tier
        self.estimate = ESTIMATE if estimate is None else estimate
        self.ore = ore
        self.near = near

    def there(self, radius):
        """Whether do may find something within radius: False where near is set and none of it that keep allows is
        (#554), so get gives up at once instead of costing the tools to gather nothing with. make_cost of the raw item
        itself is None then (#649), and of an item whose stations need it (#729); as a raw item of the item's own steps it
        still costs it, as if there were some."""
        return self.near is None or lib.find.nearest(self.near, radius, self.keep) is not None

    def blocks(self, n):
        """How many blocks drop n of the item (#754): n where drops is None, else by the mean drops with the Fortune of
        the best tool of its kind carried (lib.tools.best), which lib.tools.hold holds for do."""
        if self.drops is None:
            return n
        best = None if self.tool is None else lib.tools.best(self.tool)
        level = 0 if best is None else best.enchantments.get(FORTUNE, 0)
        return int(n / self.drops(level) + 0.99)

    def least(self, n):
        """A bound cost never goes under, for make_cost's limit, without the ore scan (#582): the estimate each, or for
        a source with ore, where cost may mine an exposed vein from where it stands (#681), the mining of each with the
        fastest pickaxe tier that drops it, where that is less; each of blocks(n)."""
        n = self.blocks(n)
        if self.ore is None:
            return self.estimate * n
        tiers = lib.tools.TIERS
        ok = tiers[:tiers.index(self.tier) + 1] if self.tier in tiers else tiers
        return min(self.estimate, min([lib.cost.BREAK["ore"][t] for t in ok])) * n

    def cost(self, n, radius, start=None):
        """(ticks, tier, dug) to get n: estimate each, and deep(y, branches(n)) once more where none of ore is exposed
        within radius (one flight of stairs reaches a vein); the tier of a pickaxe source is that of the nearest exposed
        ore block (lib.tools.tier_for its Pos), so a modded ore in a needs_*_tool tag gets the tier it needs, else
        self.tier. Where one is exposed, its vein (ore_vein), up to n, costs lib.cost.walk from start (None: the bot's
        Pos) to that block (#675) and the mining of each, with the best pickaxe carried where it drops the ore, else with
        that tier, as lib.dig.ore mines a vein from where it stands (#661); the rest costs the estimate, which counts a
        walk, each. n counts as blocks(n), the blocks that drop it. dug is the blocks the trip digs besides those, which
        its tool wears on too (trip; 0 where one is exposed or there is no ore, #984).
        ponytail: the nearest vein only."""
        n = self.blocks(n)
        branches = 1 if self.branches is None else self.branches(n)
        p, tier, once, each = self.parts(radius, start, branches)
        if p is None:
            return once + each * n, tier, 0 if self.ore is None else trip(self.y, branches)
        vein = max(1, min(n, len([b for b in ore_vein(p) if lib.find.allowed(b)])))
        return once + each * vein + self.estimate * (n - vein), tier, 0

    def parts(self, radius, start=None, branches=1):
        """(p, tier, once, each) of cost: the nearest exposed ore block within radius (None: none, or no ore) and the
        tier as cost gives it; the ticks once, the walk from start (None: the bot's Pos) to p, else deep(y, branches)
        for a source with ore (0 without); and those of each block, the mining of one with the best pickaxe carried
        where it drops the ore, else with that tier, else (no p) the estimate."""
        p = None if self.ore is None else lib.find.exposed(self.ore, radius)
        tier = lib.tools.tier_for(p) if p is not None and self.tool == "pickaxe" else self.tier
        if p is None:
            return None, tier, deep(self.y, branches) if self.ore is not None else 0, self.estimate
        best = lib.tools.best("pickaxe")
        mine = tier
        if best is not None and tier in lib.tools.TIERS and lib.tools.rank(best) <= lib.tools.TIERS.index(tier):
            mine = lib.tools.tier(best)
        return p, tier, lib.cost.walk(pos() if start is None else start, p), lib.cost.breaking(1, "ore", mine)


def deep(y, branches=1):
    """The ticks of lib.dig.ore's trip from the bot's height down to y, branches of lib.dig.LENGTH (lib.dig.branches)
    and back up; DEEP where y is None. ponytail: from the bot's feet; a vein found sooner is not costed."""
    if y is None:
        return DEEP
    down = max(0, pos().y - y)
    return (STEP_DOWN + STEP_UP) * down + int(BRANCH * lib.dig.LENGTH * branches)


def trip(y, branches=1):
    """The blocks deep(y, branches)'s trip digs (#984): 3 a step of lib.dig.stairs_to and 2 a block of branch; 0 where y
    is None. ponytail: as through solid stone, where a cave spares some."""
    if y is None:
        return 0
    return 3 * max(0, pos().y - y) + int(2 * lib.dig.LENGTH * branches)


def dig_up(what):
    """A Source's do for a block that drops itself, such as dirt: mines the exposed ones within radius, nearest first."""
    return breaking(what, what, "no_block")


def breaking(item, what, reason, hand=False, keep=None):
    """A Source's do that mines the exposed blocks what selects within radius (those for which keep(p) is true, where
    keep is given), nearest first, until the bot carries n more of item, which they drop; fails reason once none is left.
    With hand, it breaks them as with a bare hand, whatever tool it carries (mine(p, hand=True))."""
    def do(n, radius):
        target = count(item) + n
        skip = {}
        while count(item) < target:
            p = lib.find.exposed(what, radius, skip, keep)
            if p is None:
                return result(reason, None, {item: target - count(item)})
            skip[p] = True
            r = mine(p, hand=hand)
            if not r and r.reason == "no_space":
                return result("no_space", p)
        return result()
    return do


FORTUNE = "minecraft:fortune"


def ore_drops(level):
    """The mean bonus of the game's ore_drops loot function at a Fortune level (#754): 1, 1.33, 1.75, 2.2 for 0 to 3."""
    return 1 + level * (level + 1) / 2 / (level + 2)


# The mean drops of an ore at a Fortune level where it is not one item times ore_drops (the 26.3 loot tables, #770):
# copper 2 to 5 and lapis 4 to 9 times ore_drops, redstone 4 to 5 and a uniform 0 to level more.
ORE_DROPS = {
    "raw_copper": lambda level: 3.5 * ore_drops(level),
    "lapis_lazuli": lambda level: 6.5 * ore_drops(level),
    "redstone": lambda level: 4.5 + level / 2,
}
# The height a trip is costed to for an ore lib.dig.best_y has none of (#796): emerald, most common at 236 in mountains.
# ponytail: costed as if a mountain were near; lib.dig.ore still digs no stairs for it.
TRIP_Y = {"emerald": 236}


def ore(item, block, estimate):
    """The Source of a raw item lib.dig.ore digs, with a pickaxe that drops block."""
    return Source(lambda n, radius: lib.dig.ore(item, n, radius), "pickaxe", lib.tools.tier_for(block), estimate,
                  lib.dig.ORES[item], y=TRIP_Y.get(item, lib.dig.best_y(item)), drops=ORE_DROPS.get(item, ore_drops),
                  branches=lambda blocks: lib.dig.branches(item, blocks))


# Raw items get gathers, by id ("#minecraft:logs" for any log). A script adds its own: SOURCES["minecraft:clay_ball"] =
# Source(my_clay, tool="shovel", tier="hand"). ponytail: the estimates are rough guesses of mining plus walking.
def logs_of(kind):
    """A Source of the logs kind selects (LOGS: any) from trees, by lib.trees.logs."""
    return Source(lambda n, radius: lib.trees.logs(n, radius, kind), "axe", "hand",
                  lib.cost.BREAK["wood"]["stone"] + 8 * lib.cost.WALK, near=kind)


SOURCES = {
    LOGS: logs_of(LOGS),
    COBBLESTONE: Source(lib.dig.stone, "pickaxe", None, lib.cost.BREAK["stone"]["wooden"] + 2 * lib.cost.WALK),
    "minecraft:dirt": Source(dig_up("minecraft:dirt"), "shovel", "hand", lib.cost.BREAK["dirt"]["hand"] + 2 * lib.cost.WALK,
                             near="minecraft:dirt"),
    "minecraft:sand": Source(dig_up("minecraft:sand"), "shovel", "hand", lib.cost.BREAK["dirt"]["hand"] + 2 * lib.cost.WALK,
                             near="minecraft:sand"),
}


def flint(n, radius):
    """The flint Source's do (#720): breaks the exposed gravel within radius, nearest first, and where none is left puts
    down a gravel it carries beside it and breaks that (#746), never over its head, where it would fall on the bot
    (#754), until the bot carries n more flint; no_block once it has neither."""
    target = count(FLINT) + n
    skip = {}
    while count(FLINT) < target:
        p = lib.find.exposed(GRAVEL_BLOCK, radius, skip)
        if p is not None:
            skip[p] = True
        elif count(GRAVEL_BLOCK) > 0:
            p = lib.craft.place_beside(GRAVEL_BLOCK, above=False)
            if p is None:
                return result("no_space", None)
        else:
            return result("no_block", None, {FLINT: target - count(FLINT)})
        r = mine(p)
        if not r and r.reason == "no_space":
            return result("no_space", p)
    return result()


class Flint(Source):
    """Gravel drops flint one time in ten without fortune, else itself, so the source digs what it drops again (#746)
    and there is flint to get where gravel is carried too. Its estimate is one gravel; drops, the game's flint chance by
    the shovel's Fortune (#754), makes it ten gravel a flint without, one with Fortune III."""

    def there(self, radius):
        return count(GRAVEL_BLOCK) > 0 or super().there(radius)


FLINT = "minecraft:flint"
GRAVEL_BLOCK = "minecraft:gravel"
# Ticks to break one gravel by hand and step to the next.
GRAVEL = lib.cost.BREAK["dirt"]["hand"] + 2 * lib.cost.WALK
# The chance gravel drops flint at Fortune 0 to 3 (the 26.3 loot table's table_bonus).
FLINT_CHANCE = [0.1, 0.14285715, 0.25, 1.0]
SOURCES[FLINT] = Flint(flint, "shovel", "hand", GRAVEL, near=GRAVEL_BLOCK,
                       drops=lambda level: FLINT_CHANCE[min(level, len(FLINT_CHANCE) - 1)])
# Copper, lapis and redstone measured as raw iron, one exposed ore after another (#779), gold, diamond and emerald as
# redstone (#789): an ore a walk and its mining, quicker with the iron pickaxe the last four need. Where none is exposed,
# deep() costs the digging down to it, so how rare an ore is stays out of its estimate.
for item, ore_block, estimate in [("coal", "coal_ore", 40), ("raw_iron", "iron_ore", 60), ("raw_copper", "copper_ore", 60),
                                  ("raw_gold", "gold_ore", 50), ("redstone", "redstone_ore", 50),
                                  ("lapis_lazuli", "lapis_ore", 60), ("diamond", "diamond_ore", 50),
                                  ("emerald", "emerald_ore", 50)]:
    SOURCES["minecraft:" + item] = ore(item, ore_block, estimate)
# Saplings and apples break from leaves by hand, one in chance leaves broken (the 26.3 loot tables, no fortune); LEAF
# ticks a leaves block (hardness 0.2: 6 by hand) and the step to the next. By hand, as shears or a silk touch tool would
# break them into leaves (#609), and only a grown tree's: persistent leaves are a player's, placed. The mangrove
# propagule drops from no leaves: it hangs from them, and drops only ripe (age 4, its loot table's condition), see below.
LEAF = 6 + lib.cost.WALK


def climbing(do):
    """A Source's do that takes back, after do, the scaffold its walks placed (lib.trees.take_back): the walks to leaves
    high up pillar (#654). What the caller's walks placed before and did not take back yet stands and stays listed, so a
    script on its own pillar keeps it (#657)."""
    def climbed(n, radius):
        earlier = placed_scaffold(False)
        r = do(n, radius)
        lib.trees.take_back(earlier)
        return r
    return climbed


def grown(p):
    """Whether the leaves at p are a tree's, not placed by a player (persistent)."""
    return not block(p).state.get("persistent", False)


for item, leaves, chance in [("oak_sapling", ["oak_leaves"], 20), ("spruce_sapling", ["spruce_leaves"], 20),
                             ("birch_sapling", ["birch_leaves"], 20), ("jungle_sapling", ["jungle_leaves"], 40),
                             ("acacia_sapling", ["acacia_leaves"], 20), ("dark_oak_sapling", ["dark_oak_leaves"], 20),
                             ("pale_oak_sapling", ["pale_oak_leaves"], 20), ("cherry_sapling", ["cherry_leaves"], 20),
                             ("azalea", ["azalea_leaves"], 20), ("flowering_azalea", ["flowering_azalea_leaves"], 20),
                             ("poplar_sapling", ["orange_poplar_leaves", "red_poplar_leaves", "yellow_poplar_leaves"], 20),
                             ("apple", ["oak_leaves", "dark_oak_leaves"], 200)]:
    SOURCES["minecraft:" + item] = Source(climbing(breaking("minecraft:" + item, leaves, "no_tree", True, grown)),
                                          estimate=LEAF * chance, near=leaves, keep=grown)


def ripe(p):
    """Whether the mangrove propagule at p hangs from leaves and is ripe (age 4), so it drops itself when broken: a
    younger one drops nothing, and a planted one is a farm's (#650)."""
    s = block(p).state
    return s.get("hanging", False) and s.get("age", 0) == 4


# A mangrove's leaves get hanging propagules as it grows (TreeFeatures MANGROVE and TALL_MANGROVE's attached_to_leaves
# decorator, at a random age; measured over 188 grown trees some 6 a tree, 1.2 of them ripe). One a block broken, by
# hand, so no tool. ponytail: unripe ones are left to ripen, not bone-mealed.
SOURCES["minecraft:mangrove_propagule"] = Source(
    climbing(breaking("minecraft:mangrove_propagule", "minecraft:mangrove_propagule", "no_tree", True, ripe)),
    estimate=LEAF, near="minecraft:mangrove_propagule", keep=ripe)


# Ticks to kill an animal (#719), for each of its health: with a sword, a cow's 10 in two or three blows 13 ticks apart
# and the pick-up, some 30; by hand ten 1-damage blows, one each time the cow's invulnerability ends (FightGameTest
# attackKillsACow: 113 ticks with a 5-block walk), some 90; and the walk from one animal to the next.
SWORD_KILL = 3
HAND_KILL = 9
BETWEEN = 8 * lib.cost.WALK


class Hunt(Source):
    """The Source of what animals drop (#719): mobs, an entity selector, within radius (at most entities' 64), nearest
    first and grown (a baby drops nothing, #742), killed with attack and a sword where the bot carries one; per is how
    many of item one drops on average, health how much one has. There is nothing to get where none is in range, so get
    fails at once with no_animal and make_cost is None. ponytail: an animal that cannot be got to is skipped."""

    def __init__(self, item, mobs, per, health):
        super().__init__(self.hunt, estimate=int(SWORD_KILL * health / per))
        self.item = item
        self.mobs = mobs
        self.per = per
        self.health = health

    def near_mobs(self, radius):
        return [m for m in entities(self.mobs, min(radius, 64)) if not m.baby]

    def there(self, radius):
        return len(self.near_mobs(radius)) > 0

    def cost(self, n, radius, start=None):
        """(ticks, None, 0): the walk from start (None: the bot's Pos) to the nearest animal, a kill of its health for each
        per of n, with a sword where one is carried, else by hand, and BETWEEN from each animal to the next."""
        mobs = self.near_mobs(radius)
        walk = 0 if not mobs else lib.cost.walk(pos() if start is None else start, mobs[0].pos)
        kills = max(1, int(-(-n // self.per)))
        kill = (SWORD_KILL if count("#minecraft:swords") else HAND_KILL) * self.health
        return int(walk + kills * kill + (kills - 1) * BETWEEN), None, 0

    def hunt(self, n, radius):
        target = count(self.item) + n
        tried = []
        while count(self.item) < target:
            mobs = [m for m in self.near_mobs(radius) if m not in tried]
            if not mobs:
                return result("no_animal", None, {self.item: target - count(self.item)})
            if not tried:
                lib.tools.hold("sword")
            tried.append(mobs[0])
            if attack(mobs[0]):
                pick_up(self.item, 6)
        return result()


# Each animal's health (Cow, Pig, Sheep and Chicken createAttributes).
for item, mobs, per, health in [("beef", ["cow", "mooshroom"], 2, 10), ("leather", ["cow", "mooshroom"], 1, 10),
                                ("porkchop", "pig", 2, 10), ("mutton", "sheep", 1.5, 8), ("chicken", "chicken", 1, 4),
                                ("feather", "chicken", 1, 4)]:
    SOURCES["minecraft:" + item] = Hunt("minecraft:" + item, mobs, per, health)


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
    # The logs got as any log (key): one the plan still asks for after that, the recipe takes only that kind (#933).
    widened = []
    start = by_stations()
    # The gather costs of fuel, measured once and only where a plan smelts and gathers a fuel, coal or a log (for planks),
    # so may be short of fuel (#954): not where fuel is carried, so a raw item with no source fails as soon as before.
    costs = None
    for _ in range(ROUNDS):
        p = plan_by(start, item, n, costs)
        if costs is None and smelted(p) and [r for r in p["gather"] if key(r) in (COAL, LOGS)]:
            costs = fuel_costs(radius, logs=fuel_logs(p))
            p = plan_by(start, item, n, costs)
        while p["gather"] and intermediate(p, item, chests, tried):
            p = plan_by(start, item, n, costs)
        if item in p["gather"]:
            return result("no_recipe", None, {item: n - count(item)})
        if p["no_tool"]:
            # A furnace craft_here puts down comes back only with a pickaxe: none made where a raw item cannot be got,
            # such as meat with no animal near (#741).
            missing = short(p, {}, radius)
            if missing is not None:
                return missing[0].do(missing[1], radius)
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
            if raw in widened:
                r = gather(logs_of(raw), k, radius)
            else:
                if key(raw) == LOGS and raw != LOGS:
                    widened.append(raw)
                r = get(raw, count(key(raw)) + k, radius)
            if not r["ok"]:
                return r
    back_to(start)
    return lib.craft.craft_here(item, n)


def by_stations():
    """The spot get begins on with the crafting table and furnace within lib.craft.REACH there, by Pos (None: none), for
    back_to. ponytail: a smoker or blast furnace is not walked back to."""
    return pos(), find_nearest("crafting_table", radius=lib.craft.REACH), find_nearest("furnace", radius=lib.craft.REACH)


def back_to(start):
    """Walks back to the spot of start (by_stations) where taking from a chest or gathering took the bot out of reach of
    the crafting table or furnace within lib.craft.REACH there (#692, #698). lib.craft.plan and craft_here look for
    stations within REACH of where the bot stands, so from beside a chest far from them they would plan to make and put
    down others, and fail no_logs with no wood, or no_tool with no pickaxe to mine a furnace back; whether they did hung
    on where the bot stood to open the chest, which of several equal paths it walked. The spot itself, as both stations
    were within REACH of it."""
    spot, table, furnace = start
    if (table is not None and not lib.craft.table_near()
            or furnace is not None and find_nearest("furnace", radius=lib.craft.REACH) is None):
        goto(spot)


def plan_by(start, item, n, costs=None):
    """lib.craft.plan(item, n, costs=costs) once the bot is back by its stations (back_to)."""
    back_to(start)
    return lib.craft.plan(item, n, costs=costs)


def fuel_costs(radius, tools=None, logs=0):
    """needs' costs of the raw items a plan may gather, in ticks (#954, #966), each a pair (once, each), so a big smelt
    counts the walk once (#960): coal, once the walk to an exposed coal ore (else the trip down) and the tool_cost of a
    pickaxe of the tier it needs (DEEP where none can be had), each the mining of a block (Source.parts); a log, once the
    walk to the nearest log within radius (DEEP where there is none), each its breaking with the best axe carried, else
    by hand, or where no axe is carried and a wooden or stone one made (tool_cost) and logs, the logs the plan may fell,
    broken with it cost less (#974), the cheaper, once that axe more and each its breaking with it; any other gatherable
    item (the antcraft:gatherable tag) the estimate of its SOURCES entry each, or DEEP where get has none. tools as
    make_cost's. needs counts each smelt's lib.cost.SMELT with these costs, so a stone axe's cheap logs do not tip a big
    smelt to charcoal (#976).
    ponytail: no scan for the other raw items, so an ore costs its estimate, not its walk or trip; a SOURCES item outside
    the tag costs needs' 1, as an id the game does not know would stop needs; neither changes a vanilla plan's choice
    (#974: no recipe takes those items where another raw item would do, and an ore's other ways are cycles or raw items
    get has no source for); past the nearest vein coal costs as much a block, where get finds the next."""
    if tools is None:
        tools = {}
    p, tier, walk, mine = SOURCES[COAL].parts(radius)
    pickaxe = 0 if tier == "hand" else tool_cost("pickaxe", tier or "wooden", 1, radius, tools)
    log = lib.find.nearest(LOGS, radius)
    fell = DEEP if log is None else lib.cost.walk(pos(), log)
    axe = lib.tools.best("axe")
    chop = lib.cost.breaking(1, "wood", None if axe is None else lib.tools.tier(axe))
    # With no axe, the cheapest of the hand, a wooden and a stone axe made, as ensure_tool picks (#966, #974).
    extra = 0
    for tier in ("wooden", "stone") if axe is None and log is not None else ():
        each = lib.cost.breaking(1, "wood", tier)
        # The axe's make_cost only where it may pay off: it takes at least a log (#966).
        if logs * (chop - each) + extra > SOURCES[LOGS].least(1):
            made = tool_cost("axe", tier, logs, radius, tools)
            if made is not None and made + logs * each < extra + logs * chop:
                extra, chop = made, each
    fell += extra
    # Only the tag's items, so a script's SOURCES entry of an item the game does not know stops no needs (#966).
    costs = {}
    for item in tag_items(GATHERABLE):
        s = SOURCES.get(key(item))
        costs[item] = (0, DEEP if s is None else s.estimate)
    costs[COAL] = (walk + (DEEP if pickaxe is None else pickaxe), mine)
    costs[LOGS] = (fell, chop)
    return costs


# The items needs may plan to gather (data/antcraft/tags/item/gatherable.json).
GATHERABLE = "#antcraft:gatherable"


def fuel_logs(p):
    """The logs p, a plan, may fell, for fuel_costs: those it gathers, or where more, those whose planks would smelt
    what it smelts (a plank smelts 1.5 items, a log makes 4)."""
    return max(sum([k for r, k in p["gather"].items() if key(r) == LOGS]), -(-smelted(p) // 6))


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
        k -= chest_held(COAL, radius)[1]
    if k <= 0:
        return 0
    logs, wood = charcoal(k, radius)
    return logs if wood < SOURCES[COAL].cost(k, radius)[0] else 0


def chest_held(item, radius, chests=None):
    """(chests, n): the chests of lib.stock within radius (or of chests, a list of them) that hold item, nearest first,
    and how much they hold."""
    chests = lib.stock.where(item, radius) if chests is None else [c for c in chests if lib.stock.holds(c, item)]
    return chests, sum([s.count for c in chests for s in container(c) or [] if matches(s, item)])


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
    of each other raw item lib.craft.plan lists, the item itself where it is raw too: the walk to the nearest chest of
    lib.stock within radius that holds some, for as much as the chests hold, as get takes them first (#661), the rest
    from its source (coal from logs as charcoal where that is cheaper), SMELT for each item smelted, the walk to or the mining back of its crafting table and furnace (stations), the walk
    from the last chest get takes from where it takes from one, as get walks back to them (#698; ponytail: not from a
    source's ore or tree),
    and for each kind of tool their sources need the tool_cost of the highest
    tier any needs; None where a raw item has no source, or it or that tool is being got or costed, or where item is
    raw and neither the chests nor its source have any of it within radius (Source.there), as get then fails at once, and
    so for a raw item only its crafting table or furnace takes (#729). tools holds the tool
    costs found so far, by (tool id, count), for one options, under "chests" the chests of lib.stock, scanned once, and under
    "plans" the needs, lib.craft.plan and fuel costs of each (item, n) costed (#728, #961), under "fuel" the fuel_costs
    get plans with, measured once and only where a plan smelts and gathers a fuel (#961), under "logs" whether any log is near (only_kind), as a tier options costs may be costed again as
    the tool of a source (the wooden pickaxe of a stone one's cobblestone). With
    limit, None as soon as the cost is sure to be over it: a source costs at least its least, so the ore scans and tool
    costs of a dear one are left out (#582, #681)."""
    if key(item) in GETTING:
        return None
    tools = {} if tools is None else tools
    if "plans" not in tools:
        tools["plans"] = {}
    plans = tools["plans"]
    if (item, n) not in plans:
        plans[(item, n)] = [needs(item, n), None, None]
    cached = plans[(item, n)]
    p = cached[0]
    if limit is not None and least_make(p, radius, tools) > limit:
        return None
    # The raw items the item's own steps take; the others are its stations' (#729).
    own = [key(raw) for raw in p["gather"]]
    GETTING.append(key(item))
    try:
        if cached[1] is None:
            # The fuel costs get plans with, where the plan smelts and gathers a fuel (#961, as resolve, #954); after
            # least_make, so a tier it bounds out measures none: its bound leaves out the wood the plan burns (#963).
            if smelted(p) and [r for r in p["gather"] if key(r) in (COAL, LOGS)]:
                # ponytail: measured once an options, with the logs of the first plan that smelts (#966).
                if "fuel" not in tools:
                    tools["fuel"] = fuel_costs(radius, tools, fuel_logs(p))
                cached[2] = tools["fuel"]
                p = needs(item, n, costs=cached[2])
            cached[1] = planned(item, n, p, tools, cached[2])
        p = cached[1]
        costs = cached[2]
        ticks = 0
        gather = dict(p["gather"])
        # The last chest get takes from, where its walk back to the stations starts (#698).
        back = None
        smelt = smelts(p)
        if gather:
            c, back, gather, smelt = stored(p, item, n, radius, tools, costs)
            if sum([max(0, k) for k in smelt.values()]) <= 0 < smelted(p):
                # The chests hold all the plan smelts: no furnace to make, nor fuel to burn (#709).
                p = without_furnace(item, n)
                c, back, gather, smelt = stored(p, item, n, radius, tools)
            ticks += c
        ticks += lib.cost.SMELT * sum([max(0, k) for k in smelt.values()])
        need = {}
        # The raw items with no ore first, so the bound of an ore's least counts their cost (#681).
        for raw, k in sorted(gather.items(), key=lambda g: key(g[0]) in SOURCES and SOURCES[key(g[0])].ore is not None):
            if k <= 0:
                continue
            s = SOURCES.get(key(raw))
            # The item itself is in GETTING as this call costs it (#649): a raw item plans as gathering itself.
            itself = key(raw) == key(item)
            if s is None or not itself and key(raw) in GETTING:
                return None
            what = key(raw)
            if what == LOGS and raw != LOGS and not itself and only_kind(item, n, p, raw, k, radius, tools, costs):
                what, s = raw, logs_of(raw)
            # get takes a raw item from the chests first: the walk to the nearest, for as much as they hold (#555, #661).
            if "chests" not in tools:
                tools["chests"] = lib.stock.chests(radius)
            chests, held = chest_held(what, radius, tools["chests"])
            if chests:
                ticks += lib.cost.walk(pos(), chests[0])
                back = chests[0]
                k -= held
                if k <= 0:
                    continue
            # A station's raw items too: get cannot make the furnace or table without them (#729).
            # And a log of the one kind the recipe takes, as get fails no_logs for it (#938).
            if (itself or key(raw) not in own or what != key(raw)) and not s.there(radius):
                return None
            # Coal may come as charcoal for less than its least.
            if limit is not None and ticks + (0 if key(raw) == COAL else s.least(k) + smelted_pickaxe(s, tools)) > limit:
                return None
            # The source's walk starts at the chest get took the rest from. A script's cost giving (ticks, tier), as
            # before #984, digs nothing more (#986).
            c, tier, dug = (tuple(s.cost(k, radius, chests[0] if chests else None)) + (0,))[:3]
            if key(raw) == COAL and LOGS not in GETTING:
                wood = charcoal(k, radius)[1]
                if wood < c:
                    ticks += wood
                    continue
            ticks += c
            if s.tool and tier != "hand":
                t, uses = need.get(s.tool, ("wooden", 0))
                need[s.tool] = (min([t, tier or "wooden"], key=lib.tools.TIERS.index), uses + k + dug)
        ticks += stations(p, back, smelt)
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


def only_kind(item, n, p, raw, k, radius, tools, costs=None):
    """Whether make_cost is to cost raw, a log of one kind that p, the plan for n of item, gathers k of, as that kind,
    not as any log (key), as get gathers it once any log did not do (#933, #938): where no log of raw is within radius
    (other logs or none at all, #941), and the plan made with k logs of another kind carried still asks for raw, as oak
    slabs take oak planks.
    ponytail: where no log is within radius at all, only an item whose id holds raw's wood (raw less "minecraft:" and
    "_log", so oak slabs, not a wooden axe or iron one) runs the needs (3000 instructions, which choose cannot spare, and a
    fuel plan may still ask for raw); one that takes only that kind under another name, or a bamboo block's, costs as
    any log there. tools as make_cost's: whether any log is near is found once, in tools["logs"] (#947); costs the fuel
    costs make_cost plans with (#963)."""
    if "logs" not in tools:
        tools["logs"] = lib.find.nearest(LOGS, radius) is not None
    if not tools["logs"]:
        if raw[10:-4] not in item:
            return False
    elif lib.find.nearest(raw, radius) is not None:
        return False
    other = "minecraft:oak_log" if raw == "minecraft:birch_log" else "minecraft:birch_log"
    return raw in needs(item, n, also=p["also"], extra={other: k}, costs=costs)["gather"]


def planned(item, n, p, tools, costs=None):
    """lib.craft.plan(item, n, p, costs) for make_cost: where it would plan only a crafting table, p with the steps of
    needs("crafting_table"), made once for one options in tools["table"], put first, which is the same where the table
    takes none of what the bot carries: it gathers one oak log and the bot carries no planks (#737). Its four planks are
    one log's whole output, so the item's planks keep their leftovers."""
    if lib.craft.smelt_inputs(p) or not p["table"] or lib.craft.table_near():
        return lib.craft.plan(item, n, p, costs)
    if "table" not in tools:
        tools["table"] = needs("crafting_table")
    t = tools["table"]
    if t["gather"] != {LOG: 1} or count(PLANKS):
        return lib.craft.plan(item, n, p)
    gather = dict(p["gather"])
    gather[LOG] = gather.get(LOG, 0) + 1
    return {"gather": gather, "crafts": t["crafts"] + p["crafts"], "uses": t["uses"] + p["uses"], "inputs": p["inputs"],
            "fuel": p["fuel"], "table": True, "also": ["crafting_table"], "no_tool": False}


def least_make(p, radius, tools):
    """A bound make_cost never goes under, from p, a needs() plan, alone (#710): before lib.craft.plan, whose needs with
    the stations costs as much again, and the ore scans. SMELT for each item p smelts, its stations and the least of each
    raw item's source with smelted_pickaxe (coal none, as it may come as charcoal); 0 where the chests in tools["chests"]
    hold any of p's items, raw or a step, as make_cost then takes those for less. The logs p burns, as logs or as planks
    (four a log), are left out too, as make_cost plans with get's fuel costs, where coal may stand in for them (#963)."""
    if "chests" not in tools:
        tools["chests"] = lib.stock.chests(radius)
    for what in list(p["gather"]) + [step for step, k, how in p["crafts"]]:
        for c in tools["chests"]:
            if any([lib.stock.holds(c, w) for w in alike(what)]):
                return 0
    planks = sum([m for b in p["burns"] for f, m in b.items() if f.endswith("_planks")])
    burnt = sum([m for b in p["burns"] for f, m in b.items() if key(f) == LOGS]) + -(-planks // 4)
    ticks = lib.cost.SMELT * smelted(p) + stations(p)
    for raw, k in p["gather"].items():
        s = SOURCES.get(key(raw))
        if key(raw) == LOGS:
            k, burnt = k - burnt, max(0, burnt - k)
        if s is not None and key(raw) != COAL and k > 0:
            ticks += s.least(k) + smelted_pickaxe(s, tools)
    return ticks


# The ingot a pickaxe of each tier is made of, where it is smelted, for smelted_pickaxe (#700).
INGOTS = {"copper": "minecraft:copper_ingot", "iron": "minecraft:iron_ingot", "golden": "minecraft:gold_ingot"}


def smelted_pickaxe(s, tools):
    """A bound make_cost's tool_cost of the pickaxe source s needs never goes under, without a plan or scan (#700): where
    the tier of s is made of an ingot (INGOTS) and the bot carries no pickaxe of that tier or better, nor do the chests in
    tools["chests"] hold one or that ingot, a SMELT for each of the three ingots it does not carry; else 0. ponytail:
    ingots from nuggets or blocks carried are not seen, and only the smelting is counted, not the raw items."""
    ingot = INGOTS.get(s.tier) if s.tool == "pickaxe" else None
    if ingot is None:
        return 0
    ok = lib.tools.TIERS[:lib.tools.TIERS.index(s.tier) + 1]
    if any([lib.tools.tier(t) in ok for t in lib.tools.carried("pickaxe")]):
        return 0
    for c in tools.get("chests", []):
        for t in container(c) or []:
            if matches(t, ingot) or matches(t, "#minecraft:pickaxes") and lib.tools.tier(t) in ok:
                return 0
    return lib.cost.SMELT * max(0, 3 - count(ingot))


def smelted(p):
    """How many items p, a plan, smelts."""
    return sum(smelts(p).values())


def smelts(p):
    """How many of each step p, a plan, smelts, by step."""
    out = {}
    for step, k, how in p["crafts"]:
        if how == "smelt":
            out[step] = out.get(step, 0) + k
    return out


def without_furnace(item, n):
    """lib.craft.plan(item, n) as it is where a furnace is in reach: with the crafting table it puts down first, where
    one is not near, but not the furnace (#709)."""
    also = ["crafting_table"] if needs(item, n)["table"] and not lib.craft.table_near() else None
    p = needs(item, n, also=also)
    p["also"] = also
    return p


def stations(p, start=None, smelting=None):
    """The ticks the stations of p, a plan, add (#630): for its crafting table and its furnace (or smoker or blast furnace)
    each, the walk from start (None: the bot's Pos) to the one lib.craft.plan counts within lib.craft.REACH, as craft_here walks to one craft or smelt does
    not see, else the ticks to mine back the one craft_here puts down, with the best axe or pickaxe carried (a furnace at
    least with a wooden pickaxe, which get gets before it smelts), a furnace once for each stack of lib.craft.STACK a step
    smelts, as smelt_here puts one down for each (#635); what makes one is in p already. smelting is how many of
    each step are left to smelt (smelts; None: all p smelts), as the chests hold the rest: no furnace where none is left,
    and one for each stack of what is (#709, #729)."""
    ticks = 0
    if p["table"]:
        ticks += station(find_nearest("crafting_table", radius=lib.craft.REACH), "axe", "table", None, start=start)
    left = smelts(p) if smelting is None else smelting
    stacks = sum([(max(0, k) + lib.craft.STACK - 1) // lib.craft.STACK for k in left.values()])
    inputs = lib.craft.smelt_inputs(p) if stacks else []
    if inputs:
        ticks += station(lib.craft.furnace_at(inputs), "pickaxe", "furnace", "wooden", stacks, start)
    return ticks


def station(at, kind, material, least, n=1, start=None):
    """The walk from start (None: the bot's Pos) to at, a station's Pos; where it is None, the ticks to break n of
    material with the best tool of kind carried, else with one of tier least (None: the hand)."""
    if at is not None:
        return lib.cost.walk(pos() if start is None else start, at)
    tool = lib.tools.best(kind)
    return lib.cost.breaking(n, material, least if tool is None else lib.tools.tier(tool))


def stored(p, item, n, radius, tools, costs=None):
    """The walk to the nearest chest of lib.stock within radius that holds each step of p, a plan for n of item, as
    intermediate takes them (planks of any kind for planks); tools as make_cost's, costs the gather costs of fuel its needs take. Gives those ticks, the Pos of the
    last chest walked to (None: none), and the raw items (#580) and how many of each step (smelts, #709) the plan made
    again with what the chests hold of its steps counted as carried takes, so a carried item is counted once (#737). A
    step those taken already cover is not walked to. A chest's items count once, though a step comes twice (the table's
    planks and the item's) or matches twice (oak planks, then any planks), and a chest is walked to once (#759)."""
    ticks = 0
    last = None
    extra = {}
    walked = []
    left = p
    for step, k, how in reversed(p["crafts"]):
        if step == item:
            continue
        if "chests" not in tools:
            tools["chests"] = lib.stock.chests(radius)
        chests = tools["chests"]
        for what in alike(step):
            k = sum([m for st, m, h in left["crafts"] if st == step])
            have = [c for c in chests if lib.stock.holds(c, what)]
            free = {}
            for s in [s for c in have for s in container(c) or [] if matches(s, what)]:
                free[s.item] = free.get(s.item, -extra.get(s.item, 0)) + s.count
            held = min(k, sum([max(0, m) for m in free.values()]))
            if held <= 0:
                continue
            if have[0] not in walked:
                ticks += lib.cost.walk(pos(), have[0])
                walked.append(have[0])
            last = have[0]
            for i, m in free.items():
                m = min(held, max(0, m))
                extra[i] = extra.get(i, 0) + m
                held -= m
            left = needs(item, n, also=p.get("also") or None, extra=extra, costs=costs)
    return ticks, last, dict(left["gather"]), smelts(left)


def tool_cost(kind, tier, uses, radius, tools):
    """0 where the bot carries tools of kind of at least tier that last uses; else the walk to the nearest chest of
    lib.stock within radius with one that lasts the rest; else make_cost of as many of tier as the rest takes (#984).
    ponytail: a chest tool must last the rest on its own."""
    ok = lib.tools.TIERS[:lib.tools.TIERS.index(tier) + 1]
    rest = uses - sum([lib.tools.uses_left(s) for s in lib.tools.carried(kind) if lib.tools.tier(s) in ok])
    if rest <= 0:
        return 0
    if "chests" not in tools:
        tools["chests"] = lib.stock.chests(radius)
    found = in_chests(kind, ok, rest, radius, chests=tools["chests"])
    if found:
        return lib.cost.walk(pos(), found[0][0])
    tool = f"minecraft:{tier}_{kind}"
    n = -(-rest // lib.cost.USES[tier])
    if (tool, n) not in tools:
        tools[(tool, n)] = make_cost(tool, n, radius, tools)
    return tools[(tool, n)]


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
    # One chest scan for the chest tools and every make_cost (#947).
    costs = {"chests": lib.stock.chests(radius)}
    for chest, t in in_chests(kind, ok, need, radius, chests=costs["chests"]):
        found.append((lib.cost.walk(pos(), chest) + work(kind, uses, t or "wooden"), "chest", t, chest))
    # The hand and the carried tools never fail, so ensure_tool tries nothing after them: a tier dearer to make is left
    # out, told by its raw items' least costs before their ore scans (a diamond axe for a few logs, #582, #681).
    sure = min([o[0] for o in found if o[1] in ("hand", "carried")] or [None])
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
    # A bound before make_cost's needs, so a dear tier is left out for less (#958).
    if limit is not None and least_head(kind, t, n, radius, costs) + work(kind, uses, t) > limit:
        return None
    ticks = make_cost(tool, n, radius, costs, None if limit is None else limit - work(kind, uses, t))
    return None if ticks is None else (ticks + work(kind, uses, t), n)


# The item a tool head of each tier is made of, and its raw item, for least_head (#958).
HEADS = {"copper": ("minecraft:copper_ingot", "minecraft:raw_copper"),
         "iron": ("minecraft:iron_ingot", "minecraft:raw_iron"), "diamond": ("minecraft:diamond", "minecraft:diamond")}
# How many of its head's item each kind of tool takes.
HEAD = {"pickaxe": 3, "axe": 3, "shovel": 1, "hoe": 2, "sword": 2}


def least_head(kind, t, n, radius, tools):
    """A bound make_cost of n tools of kind and tier t never goes under, without needs (3,000 instructions, #958): for a
    tier of HEADS, the SMELT of each ingot the heads take, the least of their raw item's source and smelted_pickaxe of
    it, as least_make counts them; 0 for another tier or kind, where a chest of lib.stock (tools["chests"]) is near, or
    where the bot carries any item whose id holds t (the tool, an ingot, a nugget or a block).
    ponytail: those cases fall through to make_cost's own bound (least_make), which sees what they hold."""
    if t not in HEADS or kind not in HEAD:
        return 0
    if "chests" not in tools:
        tools["chests"] = lib.stock.chests(radius)
    if tools["chests"] or [s for s in inventory() if t in s.item]:
        return 0
    head, raw = HEADS[t]
    k = HEAD[kind] * n
    s = SOURCES[raw]
    p = {"table": True, "crafts": [] if head == raw else [(head, k, "smelt")], "inputs": {head: [raw]}}
    ticks = lib.cost.SMELT * smelted(p) + stations(p) + s.least(k) + smelted_pickaxe(s, tools)
    # The sticks' log, where the bot carries nothing they may come from.
    if not sum([count(w) for w in ("minecraft:stick", PLANKS, LOGS, "minecraft:bamboo", "#minecraft:bamboo_blocks")]):
        ticks += SOURCES[LOGS].least(1)
    return ticks


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


def in_chests(kind, ok, uses, radius, scanning=True, chests=None):
    """(chest, tier) for each tool of kind of a tier in ok that lasts uses in the chests of lib.stock within radius,
    nearest chest first; without scanning only the core and memory["chests"] (lib.stock.chests). chests: those chests,
    scanned already."""
    found = []
    for chest in (lib.stock.chests(radius, scanning) if chests is None else chests):
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
    with it cost no more than making the lowest tier of lib.craft.MADE it may (#580), or one in the colony store
    (lib.stock.store) however far (#1023), it takes before costing the options, which scans for each tier it could
    make; where no tool can be made for want of a raw item (no_wood), it fails at once with that source's reason, as
    no_logs.
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
                # The work of the lowest tier alone is a bound on making it: below it, no make cost is needed. A store
                # tool is taken however far, as get takes from the store first (#1023).
                if low and c > work(kind, uses, low[-1]) and chest not in lib.stock.store():
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
