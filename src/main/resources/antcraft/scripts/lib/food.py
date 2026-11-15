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

# The food a bot eats, best first, as (item, food points): the game's foods (foods(), modded ones too, #722), FIRST in
# its order, then the rest most points first. foods() leaves out what has an effect on eating: what poisons or hurts
# (raw chicken, rotten flesh, spider eyes, pufferfish, poisonous potatoes), chorus fruit, which teleports, golden apples,
# kept for a fight, and honey. LEFT_OUT is food it eats none of besides. Eating a stew keeps its bowl. RAW food, what a
# smoker cooks (raw meat and fish, potatoes, by the game's recipes), comes last: upkeep cooks it first where it can (#748).
FIRST = ["minecraft:golden_carrot", "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_mutton",
         "minecraft:cooked_salmon", "minecraft:cooked_chicken", "minecraft:beetroot_soup", "minecraft:baked_potato",
         "minecraft:bread", "minecraft:cooked_rabbit", "minecraft:cooked_cod"]
LEFT_OUT = ["minecraft:suspicious_stew"]  # its effects are its own component, not on eating
POINTS = dict(foods())
for i in LEFT_OUT:
    POINTS.pop(i, None)
RAW = [i for i in POINTS if "minecraft:smoker" in cooks_in(i)]
FOODS = ([(i, POINTS[i]) for i in FIRST if i in POINTS]
         + sorted([f for f in POINTS.items() if f[0] not in FIRST and f[0] not in RAW], key=lambda f: -f[1])
         + sorted([f for f in POINTS.items() if f[0] in RAW], key=lambda f: -f[1]))
ITEMS = [f[0] for f in FOODS]
COOKED = [i for i in ITEMS if i not in RAW]  # what ensure_food counts: raw food is to cook
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
WARNED = "hungry_warned"  # the memory key set once upkeep warned, until the bot carries food again


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
    """Makes the bot carry at least n food of COOKED (FOODS but RAW): taken from the chests of lib.stock within radius (the colony's core
    plainly, others borrowed), best first, else made with cook from the first of MADE it can (bread from wheat, raw meat
    cooked). Gives result(): ok, or no_item with missing, how much food it is still short."""
    if count(COOKED) < n:
        chests = lib.stock.chests(radius)
        for item in ITEMS:
            if count(COOKED) < n and chests:
                had = count(item)
                lib.stock.take_up_to(item, had + n - count(COOKED), chests=chests)
                if count(item) > had:
                    lib.borrow.keep(item)  # food is for eating: give_back does not take it back to its chest
    if count(COOKED) < n:
        made = True
        while made and count(COOKED) < n:
            made = False
            for item in MADE:
                if not made and cook(item, radius)["ok"]:
                    made = True
    if count(COOKED) < n:
        return result("no_item", None, {"food": n - count(COOKED)})
    return result()


def cook(item, radius):
    """get(item, ...) of lib.get: one more of item, or for an item smelted (cooked meat), one of its raw item got first
    where none is carried, then all the raw carried in one smelt, as a kill drops more than one (#741), but no more than
    the fuel carried burns for, so a lot of raw and little fuel cooks some (#748); one where it carries no fuel, as get
    gets fuel for it."""
    from lib.get import get  # loads most of the library: only where it must make food

    raw = needs(item)["inputs"].get(item, [])
    if raw and not sum([count(i) for i in raw]):
        r = get(raw[0], 1, radius)
        if not r["ok"]:
            return r
    # The most k whose smelt needs nothing gathered, as needs counts the fuel carried: bisected, needs is dear.
    low, high = 1, max(1, sum([count(i) for i in raw]))
    while low < high:
        k = (low + high + 1) // 2
        if needs(item, count(item) + k)["gather"]:
            high = k - 1
        else:
            low = k
    return get(item, count(item) + low, radius)


def upkeep(radius=32):
    """Call it once a round of a long loop: at LOW food or under, carrying none but RAW food, it gets some (ensure_food,
    which cooks the raw, not again for RETRY ticks after it found none, and warns once until it carries food again), then
    eats as eat_if_hungry, raw food only where it could not cook it (#748). Costs a sense or two when the bot is fed.
    Gives whether it ate."""
    f = best()
    if f is not None and memory.get(WARNED):
        memory.pop(WARNED)
    if ((f is None or f[0] in RAW) and food() <= LOW
            and (TRIED["at"] is None or game_time() - TRIED["at"] >= RETRY)):
        ensure_food(STOCK, radius)
        f = best()
        if f is None or f[0] in RAW:
            TRIED["at"] = game_time()
        if f is None:
            if not memory.get(WARNED):
                # So the player sees why the bot goes hungry (#704), once until it carries food again (#743): kept in
                # memory, so idle after a job that warned stays quiet.
                memory[WARNED] = True
                warn("hungry with no food: put food, or wheat and a crafting table, by a chest" if colony() is None else
                     "hungry with no food in the colony core: a colony.farmer keeps it stocked")
    return eat_if_hungry()
