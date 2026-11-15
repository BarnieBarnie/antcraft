# Keeping a bot fed, for any script: `import lib.food`, then lib.food.upkeep() once a round of a long loop (#703).
# A module of the mod's default library (lib/), above the resolver: it imports lib.stock and lib.borrow, and lib.get only
# where it must make food, so a fed bot never loads it.
# With botHunger off food stays at 20, so upkeep does nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.borrow
import lib.stock
from lib.result import result

# The food a bot eats, best first, with the food points each gives. Left out: what poisons or hurts (raw chicken, rotten
# flesh, spider eyes, pufferfish, poisonous potatoes), chorus fruit, which teleports, golden apples, kept for a fight,
# and stews and drinks but beetroot soup, which colony.farmer makes (#721); eat keeps its bowl. ponytail: the vanilla
# values by hand; a modded food is not eaten unless added here.
FOODS = [("minecraft:golden_carrot", 6), ("minecraft:cooked_beef", 8), ("minecraft:cooked_porkchop", 8),
         ("minecraft:cooked_mutton", 6), ("minecraft:cooked_salmon", 6), ("minecraft:cooked_chicken", 6),
         ("minecraft:beetroot_soup", 6),
         ("minecraft:baked_potato", 5), ("minecraft:bread", 5), ("minecraft:cooked_rabbit", 5),
         ("minecraft:cooked_cod", 5), ("minecraft:pumpkin_pie", 8), ("minecraft:apple", 4), ("minecraft:carrot", 3),
         ("minecraft:beef", 3), ("minecraft:porkchop", 3), ("minecraft:rabbit", 3), ("minecraft:mutton", 2),
         ("minecraft:cod", 2), ("minecraft:salmon", 2), ("minecraft:melon_slice", 2), ("minecraft:sweet_berries", 2),
         ("minecraft:glow_berries", 2), ("minecraft:cookie", 2), ("minecraft:beetroot", 1), ("minecraft:potato", 1),
         ("minecraft:dried_kelp", 1)]
ITEMS = [f[0] for f in FOODS]
# The meals of FOODS, 5 food points or more, as colony.farmer counts the core's stock: raw crops are seeds as well.
MEALS = [f[0] for f in FOODS if f[1] >= 5]
# What ensure_food makes with lib.get where the chests hold no food, in turn: bread from wheat, the rest cooked.
MADE = ["minecraft:bread", "minecraft:baked_potato", "minecraft:cooked_beef", "minecraft:cooked_porkchop",
        "minecraft:cooked_mutton", "minecraft:cooked_chicken", "minecraft:cooked_salmon", "minecraft:cooked_cod"]
# Food points at or under which upkeep gets food where it carries none; under HEAL a hurt bot eats even if some of the
# food is wasted, as from 18 up it heals.
LOW = 14
HEAL = 18
# How much food ensure_food gets, and the ticks upkeep waits before it tries again after it found none.
STOCK = 4
RETRY = 1200
TRIED = {"at": None}


def best():
    """The best food of FOODS the bot carries, as (item, food points), or None."""
    for f in FOODS:
        if has(f[0]):
            return f
    return None


def eat_if_hungry():
    """Eats the best food carried while it would waste none of it (20 less food() is at least its points), or while the
    bot is hurt under HEAL food. Gives whether it ate."""
    ate = False
    while food() < 20:
        f = best()
        if f is None or (20 - food() < f[1] and not (food() < HEAL and health() < max_health())):
            break
        if not eat(f[0]):
            break
        ate = True
    return ate


def ensure_food(n=STOCK, radius=32):
    """Makes the bot carry at least n food of FOODS: taken from the chests of lib.stock within radius (the colony's core
    plainly, others borrowed), best first, else made one at a time with lib.get's get from the first of MADE it can (bread
    from wheat, raw meat cooked). Gives result(): ok, or no_item with missing, how much food it is still short."""
    if count(ITEMS) < n:
        chests = lib.stock.chests(radius)
        for item in ITEMS:
            if count(ITEMS) < n and chests:
                had = count(item)
                lib.stock.take_up_to(item, had + n - count(ITEMS), chests=chests)
                if count(item) > had:
                    lib.borrow.keep(item)  # food is for eating: give_back does not take it back to its chest
    if count(ITEMS) < n:
        from lib.get import get  # loads most of the library: only where it must make food

        made = True
        while made and count(ITEMS) < n:
            made = False
            for item in MADE:
                if not made and get(item, count(item) + 1, radius)["ok"]:
                    made = True
    if count(ITEMS) < n:
        return result("no_item", None, {"food": n - count(ITEMS)})
    return result()


def upkeep(radius=32):
    """Call it once a round of a long loop: at LOW food or under, carrying none, it gets some (ensure_food, not again for
    RETRY ticks after it found none, and warns), then eats as eat_if_hungry. Costs a sense or two when the bot is fed. Gives whether it
    ate."""
    if food() <= LOW and best() is None and (TRIED["at"] is None or game_time() - TRIED["at"] >= RETRY):
        ensure_food(STOCK, radius)
        if best() is None:
            TRIED["at"] = game_time()
            # So the player sees why the bot goes hungry (#704).
            warn("hungry with no food: put food, or wheat and a crafting table, by a chest" if colony() is None else
                 "hungry with no food in the colony core: a colony.farmer keeps it stocked")
    return eat_if_hungry()
