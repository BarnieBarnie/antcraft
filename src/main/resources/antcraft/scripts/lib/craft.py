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
    for (step, k, how), uses, burns in zip(plan["crafts"], plan["uses"], plan["burns"]):
        if how == "craft":
            r = craft(step, k, table=table, use=uses)
            if not r:
                return lacking(result(r.reason), item, n, step, k)
            continue
        # The fuel the plan burns for the step and how many of each, so coal it keeps for a later step is not burnt
        # (#577) and smelt_here keeps back what it burns (#945). Each input the plan smelts, as many as it smelts of it,
        # not the one in the first slot (#925), all at one furnace, so the fire burns on from one into the next (#953).
        fuel = burns or None
        r = smelt_here(uses, fuel=fuel) if furnace is None else smelt_all(uses, furnace, list(fuel) if fuel else None)
        if not r["ok"]:
            return lacking(r, item, n, step, k)
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
        r = craft(step, k, use=uses)
        if not r:
            return lacking(result(r.reason), item, n, step, k)
        if step == "minecraft:crafting_table":
            return result()
    return result()


def smelt_here(item, n=1, fuel=None):
    """smelt(item, n, fuel=fuel), and where no furnace is within reach, one placed beside the bot for it: the carried
    one, else one crafted with craft_stations, by the steps and uses of the plan that keeps back the n of item it smelts
    and its fuel (#935). item may instead be a dict of items to how many of each, as needs' uses gives a smelting step,
    all smelted at the one furnace, so the fire burns on from one into the next (#953); n is then not used. The placed
    furnace is mined back after, with anything left in it, also when the smelt fails. Gives result(): ok, smelt's
    reason (for missing_ingredients with what it lacked, smelt_lacking),
    no_tool where the bot carries no pickaxe to mine a furnace back (it places and crafts none), missing_ingredients
    with missing, what the stations lack with the input and its fuel kept back, before it crafts anything, or no_furnace where no
    furnace could be crafted or placed."""
    uses = item if isinstance(item, dict) else {item: n}
    # fuel as a dict, as needs' burns gives it: how many of each it burns, kept back so, smelt burning any of them (#945).
    burns = fuel if isinstance(fuel, dict) else None
    fuel = list(fuel) if burns else fuel
    r = smelt_all(uses, None, fuel)
    if r["ok"] or r["reason"] != "no_furnace":
        return r
    # A station within REACH, as plan counts it, that smelt did not see from where the bot stands: smelt walks to it when
    # named (#630), as craft_here does to a table.
    near = furnace_at([[s.item for s in inventory() if matches(s, what)] for what in uses])
    if near is not None:
        return smelt_all(uses, near, fuel)
    # No station within reach cooks item, so only a pickaxe brings a placed furnace back.
    if not count("#minecraft:pickaxes"):
        return result("no_tool")
    if count("furnace") == 0:
        # Planned first, so the stations are made of what is left (#935): the input, then the fuel.
        kept = []
        for what, m in uses.items():
            carried = [s.item for s in inventory() if matches(s, what)]
            if not carried:
                return result("missing_ingredients", None, {first(what): m})
            # ponytail: keeps back the first carried item what selects, the one smelt loads unless a station cooks only another.
            kept = kept + [carried[0]] * min(m, count(carried[0]))
        # The fuel smelt burns: fuel, else the best carried, as smelt picks it once the inputs are loaded, so an input
        # burns only where more of it is carried (#530).
        for f, c in burns.items() if burns else []:
            kept = kept + [f] * c
        for f in [] if burns else [fuel] if fuel else ["coal", "charcoal", "#minecraft:logs", "#minecraft:planks"]:
            burnt = [s.item for s in inventory() if matches(s, f) and count(s.item) > len([x for x in kept if x == s.item])]
            if burnt:
                spare = count(burnt[0]) - len([x for x in kept if x == burnt[0]])
                # ponytail: one for each 1.5 smelted, as wood burns, the only fuel a table or furnace takes; at most what is carried, so a short fuel is smelt's to report.
                kept = kept + [burnt[0]] * min(spare, (2 * len(kept) + 2) // 3)
                break
        p = needs("furnace", 1, also=kept + ([] if table_near() else ["crafting_table"]))
        if p["gather"]:
            return result("missing_ingredients", None, p["gather"])
        if not craft_stations(p)["ok"]:
            return result(r["reason"])
    spot = place_beside("furnace")
    if spot is None:
        return result(r["reason"])
    try:
        return smelt_all(uses, spot, fuel)
    finally:
        take_back(spot, "furnace")
        # What a failed smelt left in the furnace spills when it is mined.
        pick_up(radius=3)


def smelt_all(uses, furnace, fuel):
    """Smelts each of uses (item to how many) at furnace (None: one within reach), a stack a smelt call, burning fuel
    (a list or selector). Gives result(): ok, else smelt_lacking of the first smelt that failed."""
    for what, m in uses.items():
        while m > 0:
            r = smelt(what, min(m, STACK), furnace=furnace, fuel=fuel)
            if not r:
                return smelt_lacking(r, what, min(m, STACK), fuel)
            m -= min(m, STACK)
    return result()


def craft_stations(p):
    """Crafts each step of p, a needs() plan of a furnace and the crafting table it takes, of the items the plan uses
    for it (#935); a step that needs a table at the one within REACH, else the carried one placed beside the bot and
    mined back after. Gives result(): ok, or the reason of the first step that failed."""
    spot = placed = None
    try:
        for (step, k, how), uses in zip(p["crafts"], p["uses"]):
            r = craft(step, k, table=spot, use=uses)
            if not r and r.reason == "no_table" and spot is None:
                spot = find_nearest("crafting_table", radius=REACH)
                if spot is None and count("crafting_table"):
                    spot = placed = place_beside("crafting_table")
                if spot is not None:
                    r = craft(step, k, table=spot, use=uses)
            if not r:
                return result(r.reason)
        return result()
    finally:
        if placed is not None:
            take_back(placed, "crafting_table")


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
