# A job: hauls what gathering bots put in their depots into one sorted store the player can take from, until a player
# stops it. Run it as /antcraft bot run jobs.storekeeper [x y z [facing]], such as jobs.storekeeper 100 64 100 east: the
# store is a lib.depot "storage" grid of chests WIDTH wide from x y z (by default where the bot stands) towards facing
# (east by default), one category of CATEGORIES each, published as the colony store (lib.depot.publish_store, #1018).
# Each round it takes from every source chest what the store sorts, leaving what keep names (KEEP by default: the seeds,
# saplings, torches and bone meal the gatherers use), until its inventory is full, then stores it (lib.depot.store);
# with nothing to haul it eats (lib.food.upkeep) and waits (docs/cookbook/storekeeper.md). Another script runs it with
# run(at, facing, sources, keep); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.depot
import lib.food
import lib.storage

NAME = "storage"
WIDTH = 8  # chests a row of the store, after which it starts the next row (lib.depot.slot, #1021)
WAIT = 100  # ticks between rounds with nothing to haul
# The store's categories, in the order lib.depot.category tries them: crops before food, so carrots go with the wheat.
CATEGORIES = {
    "logs": ["#minecraft:logs"],
    "planks": ["#minecraft:planks"],
    "stone": ["#minecraft:stone_crafting_materials", "minecraft:stone", "minecraft:deepslate", "minecraft:granite",
              "minecraft:diorite", "minecraft:andesite", "minecraft:tuff", "minecraft:gravel", "minecraft:sand"],
    "ores": ["#minecraft:coal_ores", "#minecraft:iron_ores", "#minecraft:copper_ores", "#minecraft:gold_ores",
             "#minecraft:redstone_ores", "#minecraft:lapis_ores", "#minecraft:diamond_ores", "#minecraft:emerald_ores",
             "minecraft:coal", "minecraft:raw_iron", "minecraft:raw_copper", "minecraft:raw_gold",
             "minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:gold_ingot", "minecraft:iron_nugget",
             "minecraft:gold_nugget", "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:diamond",
             "minecraft:emerald", "minecraft:quartz"],
    "crops": ["minecraft:wheat", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot", "minecraft:sugar_cane",
              "minecraft:pumpkin", "minecraft:melon", "minecraft:melon_slice", "minecraft:cocoa_beans",
              "minecraft:kelp", "minecraft:bamboo", "minecraft:cactus", "minecraft:nether_wart",
              "minecraft:sweet_berries", "minecraft:brown_mushroom", "minecraft:red_mushroom"],
    "food": lib.food.ITEMS,
    "mob_drops": ["minecraft:rotten_flesh", "minecraft:bone", "minecraft:string", "minecraft:spider_eye",
                  "minecraft:gunpowder", "minecraft:arrow", "minecraft:ender_pearl", "minecraft:leather",
                  "minecraft:feather", "minecraft:slime_ball", "minecraft:ink_sac", "minecraft:egg",
                  "minecraft:white_wool", "minecraft:phantom_membrane", "minecraft:blaze_rod"],
}
# What a source chest keeps by default, as the gatherers take it back out: seeds, saplings, torches and bone meal.
KEEP = ["#minecraft:saplings", "minecraft:wheat_seeds", "minecraft:beetroot_seeds", "minecraft:melon_seeds",
        "minecraft:pumpkin_seeds", "minecraft:torch", "minecraft:bone_meal"]
# What the storekeeper keeps of its own when it stores: some food to eat.
OWN = [(lib.food.COOKED, lib.food.STOCK)]
UNSORTED = lib.storage.KEEP_KINDS + [lib.depot.CHEST]  # what store leaves on the bot where no category takes it


def sources_of(sources):
    """The source chests as (chest, depot name) pairs: sources, a list of positions (no name), or else every chest of
    every depot of kind "source" published in the colony (lib.depot.published)."""
    if sources is not None:
        return [(Pos(c), None) for c in sources]
    return [(Pos(c[1]), p["name"]) for p in lib.depot.published("source") for c in p["chests"]]


def keep_for(keep, name, chest):
    """The keep list of a source chest: keep's entry for its depot name or its position, else KEEP."""
    for k, v in (keep or {}).items():
        if k == name or k == chest:
            return v
    return KEEP


def storable(item):
    """Whether store puts item away: a category of the store takes it, or it is none of UNSORTED."""
    return lib.depot.category(NAME, item) != lib.depot.OTHER or not matches(item, UNSORTED)


def haul(chest, keep):
    """Takes from chest everything the store puts away but what keep selects, in lib.depot.store's format: item ids or
    selectors the chest keeps all of, and (selector, n) pairs it keeps n of. Gives how many items it took."""
    stacks = container(chest)
    if not stacks:
        return 0
    whole = [k for k in keep if isinstance(k, str)]
    capped = [k for k in keep if not isinstance(k, str)]
    left = [k[1] for k in capped]
    held = {}
    for s in stacks:
        held[s.item] = held.get(s.item, 0) + s.count
    took = 0
    for item, n in held.items():
        if (whole and matches(item, whole)) or not storable(item):
            continue
        for i, k in enumerate(capped):
            if matches(item, k[0]):
                c = min(left[i], n)
                n -= c
                left[i] -= c
        if n > 0:
            r = take(chest, item, n)
            took += r.value or 0
            if not r and r.reason == "no_space":
                break
    return took


def run(at=None, facing="east", sources=None, keep=None, radius=32):
    """Makes the store at at (where the bot stands by default) towards facing and hauls into it forever: each round
    stores what the bot carries but OWN, then takes from each source chest (sources_of) what keep (keep_for) leaves,
    until the inventory is full. With nothing to haul it eats and waits WAIT ticks. Where the store cannot grow (store
    fails) it warns once and hauls nothing until it can."""
    lib.depot.depot(NAME, pos() if at is None else at, facing, CATEGORIES, WIDTH)
    lib.depot.publish_store(NAME)
    warned = False
    while True:
        r = lib.depot.store(NAME, OWN, radius)
        hauled = 0
        if not r["ok"]:
            if not warned:
                warn("storekeeper: the store cannot grow, hauling stops:", r["reason"], r["missing"])
                warned = True
        else:
            warned = False
            own = lib.depot.chests(NAME)
            for chest, name in sources_of(sources):
                if chest not in own and free_slots():
                    hauled += haul(chest, keep_for(keep, name, chest))
        if not hauled:
            lib.food.upkeep(radius)
            wait(WAIT)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None, args[3] if len(args) > 3 else "east")
