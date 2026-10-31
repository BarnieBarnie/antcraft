# Crafting and smelting an item through its whole recipe tree, for any script: `import lib.craft`, then
# lib.craft.craft_all("stone_pickaxe").
# A module of the mod's default library (lib/).
#
# craft_all and craft_here gather nothing: fetch the raw items first, with take from a chest or by mining, or let
# obtain take them from chests.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.inv import take_all
from lib.shaft import result

# smelt loads at most a stack a call.
STACK = 64

# Tool tiers from best to worst. ponytail: ranked by the tier in the id, golden after stone; a modded tool of no listed
# tier is held only when the bot carries none of these.
TIERS = ["netherite", "diamond", "iron", "stone", "golden", "wooden"]
# The tiers ensure_tool makes, best first: netherite takes a smithing table, golden is worse than stone.
MADE = ["diamond", "iron", "stone", "wooden"]

# How far craft_here looks for a crafting table or furnace it need not craft. ponytail: find_nearest's sphere stands in for
# the reach of craft and smelt, so a station just outside reach is not planned for.
REACH = 4


def craft_all(item, n=1, table=None, furnace=None):
    """Makes n of item from what the bot carries: each step needs(item, n) lists, crafted at table or smelted in furnace
    (None: one within reach, else one put down with smelt_here). Gives result(): ok, missing_ingredients with missing, the raw items it lacks, before it
    crafts anything, or the reason of the first step that failed."""
    plan = needs(item, n)
    if plan["gather"]:
        return result("missing_ingredients", None, plan["gather"])
    for step, k, how in plan["crafts"]:
        if how == "craft":
            r = craft(step, k, table=table)
            if not r:
                return result(r.reason)
        else:
            while k > 0:
                # ponytail: smelt_here puts a furnace down and takes it back for each stack, fine for tools and armour.
                if furnace is None:
                    r = smelt_here(plan["inputs"][step], min(k, STACK))
                else:
                    r = result(smelt(plan["inputs"][step], min(k, STACK), furnace=furnace).reason)
                if not r["ok"]:
                    return r
                k -= min(k, STACK)
    return result()


def obtain(item, n=1, chests=None):
    """Makes the bot carry n of item: the raw items plan(item, n) lists, those of a crafting table or furnace craft_here
    must craft included, taken from chests (a list of positions; None: the colony's core, none outside a colony) one
    after the other, then craft_here(item, n). Gives result(): ok, no_tool where plan(item, n) does, or
    missing_ingredients with missing, what the chests lack, before it takes or crafts anything, or craft_here's result."""
    if count(item) >= n:
        return result()
    if chests is None:
        c = colony()
        chests = [c["core"]] if c else []
    p = plan(item, n)
    if p["no_tool"]:
        return result("no_tool")
    short = fetch(p["gather"], chests)
    if short:
        return result("missing_ingredients", None, short)
    return craft_here(item, n)


def plan(item, n=1):
    """needs(item, n), with the crafting table and furnace craft_here must craft for it planned first from the same
    items: those its steps need that the bot neither carries nor finds within REACH (a furnace takes a table). A smoker
    or blast furnace within REACH that cooks every smelted input stands in for the furnace. Its no_tool is True where a
    step smelts in a furnace craft_here could not mine back (can_take_back_furnace)."""
    p = needs(item, n)
    stations = []
    inputs = smelt_inputs(p)
    if inputs and not count("furnace") and not furnace_in_reach(inputs):
        stations.append("furnace")
    if (stations or p["table"]) and not table_near():
        stations.insert(0, "crafting_table")
    if stations:
        p = needs(item, n, also=stations)
    p["no_tool"] = bool(inputs) and not can_take_back_furnace(inputs)
    return p


def ensure_tool(kind, chests=None, upgrade=False):
    """Makes the bot hold a tool of kind ("pickaxe", "axe", "shovel", "hoe" or "sword"): the best it carries; where it
    carries none, or with upgrade none of the best tier, the best of TIERS one of chests (a list of positions; None:
    the colony's core) holds, else the best of MADE that obtain(f"{tier}_{kind}", 1, chests) makes. With upgrade only a
    tier better than the carried one is taken or made, and the worse tool is kept, a spare. Gives result(): ok, or
    obtain's result for the worst tier tried where the bot carries none and none could be taken or made."""
    if chests is None:
        c = colony()
        chests = [c["core"]] if c else []
    # The rank of the best tool carried: its index in TIERS, len(TIERS) for one of no listed tier, more for none.
    have = len(TIERS) + 1
    for i, tier in enumerate(TIERS):
        if count(f"{tier}_{kind}"):
            have = i
            break
    if have > len(TIERS) and count(f"#minecraft:{kind}s"):
        have = len(TIERS)
    if have <= len(TIERS) and not upgrade:
        return hold(kind)
    better = [f"{tier}_{kind}" for tier in TIERS[:have]]
    if have > len(TIERS):
        better.append(f"#minecraft:{kind}s")
    for tool in better:
        for chest in chests:
            if any([matches(s, tool) for s in container(chest) or []]) and take(chest, tool, 1):
                return hold(kind)
    r = result()
    for tier in MADE:
        if TIERS.index(tier) >= have:
            break
        r = obtain(f"{tier}_{kind}", 1, chests)
        if r["ok"]:
            break
    if r["ok"] or have <= len(TIERS):
        return hold(kind)
    return r


def hold(kind):
    """Equips the best tool of kind the bot carries, by TIERS; one of no listed tier only where it carries none of these.
    Gives result()."""
    for tier in TIERS:
        if equip(f"{tier}_{kind}"):
            return result()
    equip(f"#minecraft:{kind}s")
    return result()


def fetch(more, chests):
    """Takes more (item to how many more than the bot carries) from each chest in turn; gives what is still short. Where
    the chests together hold too little of any item it takes nothing and gives what they lack."""
    # ponytail: a stack two selectors of more both match counts for each, so overlapping selectors can pass this check
    # and still come up short after taking.
    stacks = []
    for chest in chests:
        stacks += container(chest) or []
    short = {}
    for item, k in more.items():
        k -= sum([s.count for s in stacks if matches(s, item)])
        if k > 0:
            short[item] = k
    if short:
        return short
    need = {}
    for item, k in more.items():
        need[item] = count(item) + k
    short = more
    for chest in chests:
        short = take_all(chest, need)
        if not short:
            break
    return short


def craft_here(item, n=1):
    """craft_all(item, n), and where a step finds no crafting table within reach, one placed beside the bot for it: the
    carried one, else one crafted from carried planks or logs. The placed table is mined back after, also when a step
    fails. Gives craft_all's result, missing_ingredients with missing where plan(item, n) lacks raw items, before it
    crafts anything, so a table or furnace is not made from what the item needs, no_tool, also before it crafts
    anything, where a step smelts in a furnace it could not mine back, or no_table where no table could be placed."""
    p = plan(item, n)
    if p["gather"]:
        return result("missing_ingredients", None, p["gather"])
    if p["no_tool"]:
        return result("no_tool")
    r = craft_all(item, n)
    if r["reason"] != "no_table":
        return r
    if count("crafting_table") == 0 and not craft_all("crafting_table")["ok"]:
        return r
    spot = place_beside("crafting_table")
    if spot is None:
        return r
    try:
        return craft_all(item, n, table=spot)
    finally:
        take_back(spot, "crafting_table")


def smelt_here(item, n=1, fuel=None):
    """smelt(item, n, fuel=fuel), and where no furnace is within reach, one placed beside the bot for it: the carried
    one, else one crafted with craft_here from what the bot carries besides the n of item it smelts. The placed furnace
    is mined back after, with anything left in it, also when the smelt fails. Gives result(): ok, smelt's reason,
    no_tool where the bot carries no pickaxe to mine a furnace back (it places and crafts none), missing_ingredients
    with missing, what the stations lack with the input and its fuel kept back, before it crafts anything, or no_furnace where no
    furnace could be crafted or placed."""
    r = smelt(item, n, fuel=fuel)
    if r or r.reason != "no_furnace":
        return result(r.reason)
    # No station within reach cooks item, so only a pickaxe brings a placed furnace back.
    if not count("#minecraft:pickaxes"):
        return result("no_tool")
    if count("furnace") == 0:
        carried = [s.item for s in inventory() if matches(s, item)]
        if not carried:
            return result("missing_ingredients")
        # ponytail: keeps back the first carried item item selects, the one smelt loads unless a station cooks only another.
        stations = ["furnace"] if table_near() else ["crafting_table", "furnace"]
        k = min(n, count(carried[0]))
        # The fuel smelt burns: fuel, else the best carried, as smelt picks it.
        for f in [fuel] if fuel else ["coal", "charcoal", "#minecraft:logs"]:
            burnt = [s.item for s in inventory() if matches(s, f)]
            if burnt:
                # ponytail: one for each 1.5 smelted, as wood burns, the only fuel a table or furnace takes; at most what is carried, so a short fuel is smelt's to report.
                stations = stations + [burnt[0]] * min(count(burnt[0]), (2 * k + 2) // 3)
                break
        p = needs(carried[0], k, also=stations)
        if p["gather"]:
            return result("missing_ingredients", None, p["gather"])
        if not craft_here("furnace")["ok"]:
            return result(r.reason)
    spot = place_beside("furnace")
    if spot is None:
        return result(r.reason)
    try:
        return result(smelt(item, n, furnace=spot, fuel=fuel).reason)
    finally:
        take_back(spot, "furnace")
        # What a failed smelt left in the furnace spills when it is mined.
        pick_up(radius=3)


def can_take_back_furnace(inputs):
    """Whether a furnace smelt_here puts down to smelt inputs comes back: a station within REACH cooks them
    (furnace_in_reach), so none is put down, or the bot carries a pickaxe to mine it."""
    return furnace_in_reach(inputs) or count("#minecraft:pickaxes") > 0


def furnace_in_reach(inputs):
    """Whether a station within REACH cooks each of inputs (lists of items, one of each to smelt, as smelt_inputs gives
    them): a furnace, or a smoker or blast furnace where every input has an item cooks_in names it for."""
    if find_nearest("furnace", radius=REACH) is not None:
        return True
    for station in ("minecraft:smoker", "minecraft:blast_furnace"):
        if all([any([station in cooks_in(i) for i in items]) for items in inputs]) and find_nearest(station, radius=REACH) is not None:
            return True
    return False


def smelt_inputs(p):
    """The inputs of the smelting steps of p, a needs() plan: a list of items to smelt from for each."""
    return [p["inputs"][step] for step, k, how in p["crafts"] if how == "smelt"]


def table_near():
    """Whether the bot carries a crafting table or finds one within REACH."""
    return count("crafting_table") > 0 or find_nearest("crafting_table", radius=REACH) is not None


def place_beside(block):
    """Places a carried block on the first free spot beside the bot's feet, beside its head or above its head, else on a
    spot beside its feet it mines out first, keeping what it mined (a bot in a 1x1 pit); that Pos, or None where none
    takes it."""
    here = pos()
    head = here.up()
    feet = [here.north(), here.south(), here.east(), here.west()]
    for spot in feet + [head.north(), head.south(), head.east(), head.west(), head.up()]:
        if place(spot, block):
            return spot
    for spot in feet:
        if mine(spot) and place(spot, block):
            return spot
    return None


def take_back(spot, block):
    """Mines the block at spot back into the inventory, picking it up where it did not fit."""
    if not mine(spot):
        pick_up(block)
