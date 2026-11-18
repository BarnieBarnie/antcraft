# Crafting and smelting an item through its whole recipe tree, for any script: `import lib.craft`, then
# lib.craft.craft_all("stone_pickaxe").
# A module of the mod's default library (lib/).
#
# craft_all and craft_here gather nothing: get the raw items first, or let lib.get.get gather and craft them.
# obtain, ensure_tool and fetch are kept for old scripts: use lib.get.get, lib.get.ensure_tool and lib.stock.fetch_all.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.result import result
from lib.stock import fetch_all
# hold lives in lib.tools; imported here, lib.craft.hold keeps working.
from lib.tools import hold

# smelt loads at most a stack a call.
STACK = 64

# How far craft_here looks for a crafting table or furnace it need not craft. ponytail: find_nearest's sphere stands in for
# the reach of craft and smelt, so a station just outside reach is not planned for; a station within it that craft or
# smelt does not see from where the bot stands is walked to, so every station plan counts is one they use (#630).
REACH = 4

# The tool tiers lib.get.ensure_tool makes, best first: netherite takes a smithing table, golden is worse than stone.
MADE = ["diamond", "iron", "copper", "stone", "wooden"]


def craft_all(item, n=1, table=None, furnace=None):
    """Makes n of item from what the bot carries: each step needs(item, n) lists, crafted at table or smelted in furnace
    (None: one within reach, else one put down with smelt_here). Each step takes the items the plan uses for it (its
    uses, #935): a craft step those first of interchangeable items (craft's use), a smelting step smelts those. Gives result(): ok, missing_ingredients with missing, the raw items it lacks, before it crafts anything, or the
    reason of the first step that failed, for missing_ingredients with what it lacked (lacking)."""
    plan = needs(item, n)
    if plan["gather"]:
        return result("missing_ingredients", None, plan["gather"])
    for (step, k, how), uses in zip(plan["crafts"], plan["uses"]):
        if how == "craft":
            r = craft(step, k, table=table, use=list(uses))
            if not r:
                return lacking(result(r.reason), item, n, step, k)
            continue
        # The fuel the plan burns for the step, so coal it keeps for a later step is not burnt (#577).
        fuel = plan["fuel"].get(step) or None
        # Each input the plan smelts, as many as it smelts of it, not the one in the first slot (#925).
        for what, m in uses.items():
            while m > 0:
                # ponytail: smelt_here puts a furnace down and takes it back for each stack, fine for tools and armour.
                if furnace is None:
                    r = smelt_here(what, min(m, STACK), fuel=fuel)
                else:
                    r = result(smelt(what, min(m, STACK), furnace=furnace, fuel=fuel).reason)
                if not r["ok"]:
                    return lacking(r, item, n, step, k)
                m -= min(m, STACK)
    return result()


def lacking(r, item, n, step, k):
    """r, the result of a step making k of step for n of item; where it failed missing_ingredients with nothing in
    missing, as craft and smelt give it, with what it lacked: the raw items needs(item, n) still lists, else the k of
    step (#925)."""
    if r["reason"] != "missing_ingredients" or r["missing"]:
        return r
    return result("missing_ingredients", None, needs(item, n)["gather"] or {step: k})


def obtain(item, n=1, chests=None):
    """Kept for old scripts: lib.get.get(item, n), which also gathers what the chests lack; chests is not used."""
    import lib.get
    return lib.get.get(item, n)


def plan(item, n=1, p=None):
    """needs(item, n) (p where the caller has it, as lib.get.make_cost does, #710), with the crafting table and furnace
    craft_here must craft for it planned first from the same items: those its steps need that the bot neither carries
    nor finds within REACH (a furnace takes a table). A smoker or blast furnace within REACH that cooks every smelted
    input stands in for the furnace. Its no_tool is True where a step smelts in a furnace craft_here could not mine back
    (can_take_back_furnace), and its also the stations planned."""
    p = needs(item, n) if p is None else p
    stations = []
    inputs = smelt_inputs(p)
    if inputs and not count("furnace") and not furnace_in_reach(inputs):
        stations.append("furnace")
    if (stations or p["table"]) and not table_near():
        stations.insert(0, "crafting_table")
    if stations:
        p = needs(item, n, also=stations)
    # The stations planned, so lib.get.stored can plan again with them (#737).
    p["also"] = stations
    p["no_tool"] = bool(inputs) and not can_take_back_furnace(inputs)
    return p


def ensure_tool(kind, chests=None, upgrade=False):
    """Kept for old scripts: lib.get.ensure_tool(kind), the best carried, else one borrowed or made; chests and upgrade
    are not used."""
    import lib.get
    return lib.get.ensure_tool(kind)


def fetch(more, chests):
    """lib.stock.fetch_all(more, chests): takes more (item to how many more than the bot carries) from each chest in
    turn, borrowed but from the colony's core; gives what is still short. Where the chests together hold too little of
    any item it takes nothing and gives what they lack."""
    return fetch_all(more, chests)


def craft_here(item, n=1):
    """craft_all(item, n), and where a step finds no crafting table within reach, the one within REACH walked to, else
    one placed beside the bot for it: the carried one, else one crafted first, as plan(item, n) plans it (craft_table,
    #930), from carried planks or logs. The placed table is
    mined back after, also when a step fails. Gives craft_all's result, missing_ingredients with missing where plan(item,
    n) lacks raw items, before it crafts anything, so a table or furnace is not made from what the item needs, no_tool,
    also before it crafts anything, where a step smelts in a furnace it could not mine back, or no_table where no table
    could be placed."""
    p = plan(item, n)
    if p["gather"]:
        return result("missing_ingredients", None, p["gather"])
    if p["no_tool"]:
        return result("no_tool")
    if "crafting_table" in p["also"]:
        r = craft_table(p, item, n)
        if not r["ok"]:
            return r
    r = craft_all(item, n)
    if r["reason"] != "no_table":
        return r
    # A table within REACH that craft did not see from where the bot stands: craft walks to it when named (#623).
    near = find_nearest("crafting_table", radius=REACH)
    if near is not None:
        return craft_all(item, n, table=near)
    if count("crafting_table") == 0:
        return r
    spot = place_beside("crafting_table")
    if spot is None:
        return r
    try:
        return craft_all(item, n, table=spot)
    finally:
        take_back(spot, "crafting_table")


def craft_table(p, item, n):
    """Crafts the crafting table plan(item, n) p plans first, by its steps up to the table, each of the items the plan
    uses for it (#930, #935): of what the bot has most, the table might take planks the plan keeps for item."""
    for (step, k, how), uses in zip(p["crafts"], p["uses"]):
        r = craft(step, k, use=list(uses))
        if not r:
            return lacking(result(r.reason), item, n, step, k)
        if step == "minecraft:crafting_table":
            return result()
    return result()


def smelt_here(item, n=1, fuel=None):
    """smelt(item, n, fuel=fuel), and where no furnace is within reach, one placed beside the bot for it: the carried
    one, else one crafted with craft_here from what the bot carries besides the n of item it smelts. The placed furnace
    is mined back after, with anything left in it, also when the smelt fails. Gives result(): ok, smelt's reason (for
    missing_ingredients with what it lacked, smelt_lacking),
    no_tool where the bot carries no pickaxe to mine a furnace back (it places and crafts none), missing_ingredients
    with missing, what the stations lack with the input and its fuel kept back, before it crafts anything, or no_furnace where no
    furnace could be crafted or placed."""
    r = smelt(item, n, fuel=fuel)
    if r or r.reason != "no_furnace":
        return smelt_lacking(r, item, n, fuel)
    # A station within REACH, as plan counts it, that smelt did not see from where the bot stands: smelt walks to it when
    # named (#630), as craft_here does to a table.
    near = furnace_at([[s.item for s in inventory() if matches(s, item)]])
    if near is not None:
        return smelt_lacking(smelt(item, n, furnace=near, fuel=fuel), item, n, fuel)
    # No station within reach cooks item, so only a pickaxe brings a placed furnace back.
    if not count("#minecraft:pickaxes"):
        return result("no_tool")
    if count("furnace") == 0:
        carried = [s.item for s in inventory() if matches(s, item)]
        if not carried:
            return result("missing_ingredients", None, {first(item): n})
        # ponytail: keeps back the first carried item item selects, the one smelt loads unless a station cooks only another.
        stations = ["furnace"] if table_near() else ["crafting_table", "furnace"]
        k = min(n, count(carried[0]))
        # The fuel smelt burns: fuel, else the best carried, as smelt picks it once the k it smelts are loaded, so the
        # input burns only where more of it is carried (#530).
        for f in [fuel] if fuel else ["coal", "charcoal", "#minecraft:logs", "#minecraft:planks"]:
            burnt = [s.item for s in inventory() if matches(s, f) and count(s.item) > (k if s.item == carried[0] else 0)]
            if burnt:
                spare = count(burnt[0]) - (k if burnt[0] == carried[0] else 0)
                # ponytail: one for each 1.5 smelted, as wood burns, the only fuel a table or furnace takes; at most what is carried, so a short fuel is smelt's to report.
                stations = stations + [burnt[0]] * min(spare, (2 * k + 2) // 3)
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
        return smelt_lacking(smelt(item, n, furnace=spot, fuel=fuel), item, n, fuel)
    finally:
        take_back(spot, "furnace")
        # What a failed smelt left in the furnace spills when it is mined.
        pick_up(radius=3)


def smelt_lacking(r, item, n, fuel):
    """result() of r, what smelt gave for n of item burning fuel; for missing_ingredients with what it lacked (#925): the
    n of item where the bot carries none of it, else one of the fuel, coal where fuel is None."""
    if r or r.reason != "missing_ingredients":
        return result(r.reason)
    if not [s for s in inventory() if matches(s, item)]:
        return result(r.reason, None, {first(item): n})
    return result(r.reason, None, {first(fuel) if fuel else "minecraft:coal": 1})


def first(what):
    """what, an item or selector, or the first of a list of them."""
    return what if isinstance(what, str) else what[0]


def can_take_back_furnace(inputs):
    """Whether a furnace smelt_here puts down to smelt inputs comes back: a station within REACH cooks them
    (furnace_in_reach), so none is put down, or the bot carries a pickaxe to mine it."""
    return furnace_in_reach(inputs) or count("#minecraft:pickaxes") > 0


def furnace_in_reach(inputs):
    """Whether a station within REACH cooks each of inputs (furnace_at)."""
    return furnace_at(inputs) is not None


def furnace_at(inputs):
    """The Pos of a station within REACH that cooks each of inputs (lists of items, one of each to smelt, as smelt_inputs
    gives them): a furnace, or a smoker or blast furnace where every input has an item cooks_in names it for; else None."""
    near = find_nearest("furnace", radius=REACH)
    if near is not None:
        return near
    for station in ("minecraft:smoker", "minecraft:blast_furnace"):
        if all([any([station in cooks_in(i) for i in items]) for items in inputs]):
            near = find_nearest(station, radius=REACH)
            if near is not None:
                return near
    return None


def smelt_inputs(p):
    """The inputs of the smelting steps of p, a needs() plan: a list of items to smelt from for each."""
    return [p["inputs"][step] for step, k, how in p["crafts"] if how == "smelt"]


def table_near():
    """Whether the bot carries a crafting table or finds one within REACH."""
    return count("crafting_table") > 0 or find_nearest("crafting_table", radius=REACH) is not None


def place_beside(block, above=True):
    """Places a carried block on the first free spot beside the bot's feet, beside its head or above its head (not with
    above False, as for gravel, which would fall on the bot, #754), else on a spot beside its feet it mines out first,
    keeping what it mined (a bot in a 1x1 pit); that Pos, or None where none takes it."""
    here = pos()
    head = here.up()
    feet = [here.north(), here.south(), here.east(), here.west()]
    for spot in feet + [head.north(), head.south(), head.east(), head.west()] + ([head.up()] if above else []):
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
