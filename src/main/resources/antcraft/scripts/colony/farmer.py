# A colony farmer: tills, plants and harvests the work spots of the colony's
# farms and stores the harvest in the Colony Core. It farms every building in
# colony_memory["buildings"] whose blueprint has the role "farm" (colony.builder
# adds what it builds there), and grows wheat, carrots, potatoes and beetroot from
# the seeds the core holds, with lib.farm. With soil to till it gets a hoe with lib.get.ensure_tool:
# carried, from the core or borrowed from another chest of the colony, or made,
# felling the colony's trees for it.
# What it harvests it replants with the same crop, and it keeps one of each
# harvest that is its own seed; spare seeds and poisonous potatoes go into the
# core. It bakes the harvest into food for the colony (#704): bread from the core's wheat at the colony's crafting
# table, potatoes in its furnace with the core's fuel, until the core holds lib.food.STOCK meals (lib.food.MEALS) for each
# member, so members take it with lib.food.upkeep. Run it on a colony member with `/antcraft bot run colony.farmer`.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core, settle, store
import lib.food
from lib.borrow import give_back
from lib.farm import CROPS, POISON, SOIL, harvest, nearest_first, plant, rows, till
from lib.stock import take_up_to


def farm_spots():
    """The soil blocks of every farm in the colony, in rows back and forth (lib.farm.rows)."""
    spots = []
    for b in (colony_memory or {}).get("buildings", []):
        plan = blueprint(b["blueprint"], b["at"], b.get("turn", 0), b.get("mirror", False))
        if plan is not None and plan["role"] == "farm":
            spots.extend(plan["work_spots"])
    return rows(spots)


# What the farmer burns to bake potatoes, best first, as smelt does.
FUELS = ["minecraft:coal", "minecraft:charcoal", "#minecraft:logs", "#minecraft:planks"]


def in_core(item):
    """How many of item (an id or selector) the core holds."""
    return sum([s.count for s in container(core()) or [] if matches(s, item)])


def short():
    """How many meals the core lacks for lib.food.STOCK a member."""
    return lib.food.STOCK * len(colony()["members"]) - in_core(lib.food.MEALS)


def stock_food():
    """Bakes bread from the core's wheat at the nearest crafting table and the core's potatoes in the nearest furnace while
    the core is short() of meals, and stores them in the core. ponytail: no station of its own; a colony without a table
    or furnace gets none of that food. A smelt waits 10 seconds a potato."""
    n = min(short(), in_core("minecraft:wheat") // 3)
    table = find_nearest("crafting_table") if n > 0 else None
    if table is not None:
        take_up_to("minecraft:wheat", 3 * n, chests=[core()])
        craft("minecraft:bread", count("minecraft:wheat") // 3, table=table)
        store("minecraft:bread")
        store("minecraft:wheat")
    n = min(short(), in_core("minecraft:potato"))
    furnace = find_nearest("furnace") if n > 0 else None
    if furnace is not None:
        seed = count("minecraft:potato")  # the one it keeps to replant
        take_up_to("minecraft:potato", seed + n, chests=[core()])
        for f in FUELS:
            if not count(FUELS):
                take_up_to(f, (2 * n + 2) // 3, chests=[core()])
        if count("minecraft:potato") > seed:
            smelt("minecraft:potato", count("minecraft:potato") - seed, furnace=furnace)
        store("minecraft:baked_potato")
        if count("minecraft:potato") > seed:
            put(core(), "minecraft:potato", count("minecraft:potato") - seed)
        for f in FUELS:
            store(f)


core()  # a bot in no colony says so and stops
# Imported after it: lib.get and what it imports take a bot in no colony some ticks to load.
from lib.get import ensure_tool
settle()
while True:
    lib.food.upkeep()
    spots = farm_spots()
    soil = len([s for s in spots if block(s).id in SOIL])
    if soil:
        ensure_tool("hoe", soil)
    bare = len([s for s in spots if block(s.up()).is_air])
    for c in CROPS:
        if bare > 0:
            take_up_to(c[1], bare, chests=[core()])
            bare -= count(c[1])
    # As lib.farm.tend: harvest and replant, till, then plant the rest, each pass from the end nearest the bot (#646).
    harvest(nearest_first(spots))
    till(nearest_first(spots))
    plant(nearest_first(spots))
    pick_up([i for c in CROPS for i in c[1:3]] + [POISON])
    # Keeps the seeds the bare spots still need, wheat first, and one carrot and
    # one potato; the rest goes into the core.
    bare = len([s for s in spots if block(s.up()).is_air])
    for c in CROPS:
        keep = min(count(c[1]), bare)
        bare -= keep
        if c[2] == c[1]:
            keep = max(keep, 1)
        else:
            store(c[2])
        if count(c[1]) > keep:
            put(core(), c[1], count(c[1]) - keep)
    store(POISON)
    stock_food()
    give_back()
    wait(200 if spots else 400)
