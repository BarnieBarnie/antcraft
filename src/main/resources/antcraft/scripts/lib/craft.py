# Crafting an item through its whole recipe tree, for any script: `import lib.craft`, then
# lib.craft.craft_all("stone_pickaxe").
# A module of the mod's default library (lib/).
#
# It gathers nothing: fetch the raw items first, with take from a chest or by mining.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.shaft import result

# smelt loads at most a stack a call.
STACK = 64


def craft_all(item, n=1, table=None, furnace=None):
    """Makes n of item from what the bot carries: each step needs(item, n) lists, crafted at table or smelted in furnace
    (None: one within reach). Gives result(): ok, missing_ingredients with missing, the raw items it lacks, before it
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
                r = smelt(plan["inputs"][step], min(k, STACK), furnace=furnace)
                if not r:
                    return result(r.reason)
                k -= min(k, STACK)
    return result()


def craft_here(item, n=1):
    """craft_all(item, n), and where a step finds no crafting table within reach, one placed beside the bot for it: the
    carried one, else one crafted from carried planks or logs. The placed table is mined back after, also when a step
    fails. Gives craft_all's result, or no_table where no table could be crafted or placed."""
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
