# A colony farmer: tills, plants and harvests the work spots of the colony's
# farms and stores the harvest in the Colony Core. It farms every building in
# colony_memory["buildings"] whose blueprint has the role "farm" (colony.builder
# adds what it builds there), carries a hoe from the core when it has none, and
# grows wheat, carrots, potatoes and beetroot from the seeds the core holds.
# What it harvests it replants with the same crop, and it keeps one of each
# harvest that is its own seed; spare seeds and poisonous potatoes go into the
# core. Run it on a colony member with `/antcraft bot run colony.farmer`.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core, fetch, store

# (crop block, seed item, harvest item, ripe age); wheat first, so it is planted
# where the bot carries several kinds of seed.
CROPS = [
    ("minecraft:wheat", "minecraft:wheat_seeds", "minecraft:wheat", 7),
    ("minecraft:carrots", "minecraft:carrot", "minecraft:carrot", 7),
    ("minecraft:potatoes", "minecraft:potato", "minecraft:potato", 7),
    ("minecraft:beetroots", "minecraft:beetroot_seeds", "minecraft:beetroot", 3),
]
POISON = "minecraft:poisonous_potato"
SOIL = ["minecraft:dirt", "minecraft:grass_block"]


def farm_spots():
    """The soil blocks of every farm in the colony."""
    spots = []
    for b in (colony_memory or {}).get("buildings", []):
        plan = blueprint(b["blueprint"], b["at"], b.get("turn", 0), b.get("mirror", False))
        if plan is not None and plan["role"] == "farm":
            spots.extend(plan["work_spots"])
    return spots


def tend(soil):
    """Harvests the crop on soil when it is ripe, tills the soil and plants the
    seeds of the crop it harvested, or else of the first crop it has seeds of."""
    crop = block(soil.up())
    seeds = [c[1] for c in CROPS]
    for c in CROPS:
        if crop.id == c[0] and crop.state["age"] >= c[3]:
            mine(soil.up())
            crop = block(soil.up())
            seeds = [c[1]] + seeds
    if block(soil).id in SOIL and crop.is_air:
        use(soil, "#minecraft:hoes")
    if block(soil).id == "minecraft:farmland" and block(soil.up()).is_air:
        for seed in seeds:
            if has(seed):
                place(soil.up(), seed)
                break


while True:
    spots = farm_spots()
    if not has("#minecraft:hoes"):
        for hoe in ["minecraft:iron_hoe", "minecraft:stone_hoe", "minecraft:wooden_hoe"]:
            if fetch(hoe, 1):
                break
    bare = len([s for s in spots if block(s.up()).is_air])
    for c in CROPS:
        if bare > 0:
            fetch(c[1], bare)
            bare -= count(c[1])
    for soil in spots:
        tend(soil)
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
    wait(200 if spots else 400)
