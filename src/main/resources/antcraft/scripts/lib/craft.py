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
                    r = smelt(plan["inputs"][step], min(k, STACK), furnace=furnace)
                if not r:
                    return result(r.reason)
                k -= min(k, STACK)
    return result()


def obtain(item, n=1, chests=None):
    """Makes the bot carry n of item: the raw items plan(item, n) lists, those of a crafting table or furnace craft_here
    must craft included, taken from chests (a list of positions; None: the colony's core, none outside a colony) one
    after the other, then craft_here(item, n). Gives result(): ok, missing_ingredients with missing, what the chests
    lack, before it crafts anything, or craft_here's result."""
    if count(item) >= n:
        return result()
    if chests is None:
        c = colony()
        chests = [c["core"]] if c else []
    short = fetch(plan(item, n)["gather"], chests)
    if short:
        return result("missing_ingredients", None, short)
    return craft_here(item, n)


def plan(item, n=1):
    """needs(item, n), with the crafting table and furnace craft_here must craft for it planned first from the same
    items: those its steps need that the bot neither carries nor finds within REACH (a furnace takes a table)."""
    p = needs(item, n)
    stations = []
    if "smelt" in [step[2] for step in p["crafts"]] and not count("furnace") and find_nearest("furnace", radius=REACH) is None:
        stations.append("furnace")
    if (stations or p["table"]) and not count("crafting_table") and find_nearest("crafting_table", radius=REACH) is None:
        stations.insert(0, "crafting_table")
    return needs(item, n, also=stations) if stations else p


def ensure_tool(kind, chests=None):
    """Makes the bot hold a tool of kind ("pickaxe", "axe", "shovel", "hoe" or "sword"): the best it carries, else the
    best of MADE that obtain(f"{tier}_{kind}", 1, chests) makes. Gives result(): ok, or obtain's result for the wooden
    tool where no tier could be made."""
    for tier in TIERS:
        if equip(f"{tier}_{kind}"):
            return result()
    if equip(f"#minecraft:{kind}s"):
        return result()
    for tier in MADE:
        r = obtain(f"{tier}_{kind}", 1, chests)
        if r["ok"]:
            equip(f"{tier}_{kind}")
            return r
    return r


def fetch(more, chests):
    """Takes more (item to how many more than the bot carries) from each chest in turn; gives what is still short."""
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
    crafts anything, so a table or furnace is not made from what the item needs, or no_table where no table could be
    placed."""
    short = plan(item, n)["gather"]
    if short:
        return result("missing_ingredients", None, short)
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
    one, else one crafted from 8 cobblestone with craft_here. The placed furnace is mined back after, with anything left
    in it, also when the smelt fails; that takes a pickaxe. Gives smelt's Result, or its no_furnace where no furnace
    could be crafted or placed."""
    r = smelt(item, n, fuel=fuel)
    if r or r.reason != "no_furnace":
        return r
    if count("furnace") == 0 and not craft_here("furnace")["ok"]:
        return r
    spot = place_beside("furnace")
    if spot is None:
        return r
    try:
        return smelt(item, n, furnace=spot, fuel=fuel)
    finally:
        take_back(spot, "furnace")
        # What a failed smelt left in the furnace spills when it is mined.
        pick_up(radius=3)


def place_beside(block):
    """Places a carried block on the first free spot beside the bot, at its feet; that Pos, or None where none takes it."""
    here = pos()
    for spot in (here.north(), here.south(), here.east(), here.west()):
        if place(spot, block):
            return spot
    return None


def take_back(spot, block):
    """Mines the block at spot back into the inventory, picking it up where it did not fit."""
    if not mine(spot):
        pick_up(block)
