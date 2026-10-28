# A colony farmer: tills, plants and harvests the work spots of the colony's
# farms and stores the wheat in the Colony Core. It farms every building in
# colony_memory["buildings"] whose blueprint has the role "farm" (colony.builder
# adds what it builds there), carries a hoe from the core when it has none, and
# replants from the seeds it harvests. Run it on a colony member with
# `/antcraft bot run colony.farmer`.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core, fetch, store

CROP = "minecraft:wheat"
SEEDS = "minecraft:wheat_seeds"
RIPE = 7  # the age of grown wheat
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
    """Harvests the crop on soil when it is ripe, tills the soil and plants seeds."""
    crop = block(soil.up())
    if crop.id == CROP and crop.state["age"] >= RIPE:
        mine(soil.up())
        crop = block(soil.up())
    if block(soil).id in SOIL and crop.is_air:
        use(soil, "#minecraft:hoes")
    if block(soil).id == "minecraft:farmland" and block(soil.up()).is_air and has(SEEDS):
        place(soil.up(), SEEDS)


while True:
    spots = farm_spots()
    if not has("#minecraft:hoes"):
        for hoe in ["minecraft:iron_hoe", "minecraft:stone_hoe", "minecraft:wooden_hoe"]:
            if fetch(hoe, 1):
                break
    fetch(SEEDS, len(spots))
    for soil in spots:
        tend(soil)
    pick_up([CROP, SEEDS])
    store(CROP)
    wait(200 if spots else 400)
