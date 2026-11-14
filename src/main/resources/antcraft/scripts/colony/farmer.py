# A colony farmer: tills, plants and harvests the work spots of the colony's
# farms and stores the harvest in the Colony Core. It farms every building in
# colony_memory["buildings"] whose blueprint has the role "farm" (colony.builder
# adds what it builds there), and grows wheat, carrots, potatoes and beetroot from
# the seeds the core holds, with lib.farm. With soil to till it gets a hoe with lib.get.ensure_tool:
# carried, from the core or borrowed from another chest of the colony, or made,
# felling the colony's trees for it.
# What it harvests it replants with the same crop, and it keeps one of each
# harvest that is its own seed; spare seeds and poisonous potatoes go into the
# core. Run it on a colony member with `/antcraft bot run colony.farmer`.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core, store
from lib.borrow import give_back
from lib.farm import CROPS, POISON, SOIL, harvest, plant, till
from lib.stock import take_up_to


def farm_spots():
    """The soil blocks of every farm in the colony."""
    spots = []
    for b in (colony_memory or {}).get("buildings", []):
        plan = blueprint(b["blueprint"], b["at"], b.get("turn", 0), b.get("mirror", False))
        if plan is not None and plan["role"] == "farm":
            spots.extend(plan["work_spots"])
    return spots


def tend(soil):
    """Harvests the crop on soil when it is ripe and replants it, else tills the soil and plants the first crop it has
    seeds of (lib.farm)."""
    harvest([soil])
    till([soil])
    plant([soil])


core()  # a bot in no colony says so and stops
# Imported after it: lib.get and what it imports take a bot in no colony some ticks to load.
from lib.get import ensure_tool
while True:
    spots = farm_spots()
    soil = len([s for s in spots if block(s).id in SOIL])
    if soil:
        ensure_tool("hoe", soil)
    bare = len([s for s in spots if block(s.up()).is_air])
    for c in CROPS:
        if bare > 0:
            take_up_to(c[1], bare, chests=[core()])
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
    give_back()
    wait(200 if spots else 400)
