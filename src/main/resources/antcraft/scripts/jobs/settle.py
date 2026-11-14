# A job: settles a new base from an empty-handed bot. Run it as /antcraft bot run jobs.settle [x y z [radius [hut]]],
# such as jobs.settle 10 64 10: the lowest corner of the house, by default where the bot stands (or where a stopped run
# started), how far to look for what it needs, in blocks (32 by default), and the house's blueprint (the bundled starter
# house by default). One project of lib.project, so a stopped run resumes at the step it was on: it gets a kit, levels
# the plot, builds the house, places a chest and a crafting table beside it, plants a small wheat field round a water
# source, stores the rest and sets home inside the house (docs/cookbook/settle.md).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.bootstrap
import lib.farm
import lib.project
import lib.storage
import lib.terrain
from lib.build import build_here
from lib.get import ensure_tool, get
from lib.project import Step

HUT = "antcraft:starter_house"  # 5 by 4 by 5, a floor, its door at (2, 1, 0); any blueprint that fits in 5 by 5 will do
# The tools first, then what is placed, and dirt to climb on while building.
KIT = [("stone_pickaxe", 1), ("stone_axe", 1), ("stone_shovel", 1), ("stone_hoe", 1), ("crafting_table", 1),
       ("chest", 1), ("dirt", 16)]
SEED = "minecraft:wheat_seeds"
TALL = 6  # how high over the ground the plot is cleared
DEEP = 3  # how deep a hole in the plot is filled

# Without x y z, a rerun resumes where the stopped run started, not where the bot stands now.
at = Pos(int(ARGS[0]), int(ARGS[1]), int(ARGS[2])) if len(ARGS) > 2 else memory.get("settle_at", pos())
memory["settle_at"] = at
radius = int(ARGS[3]) if len(ARGS) > 3 else 32
hut = ARGS[4] if len(ARGS) > 4 else HUT
ground = at.y - 1
# The house at at, the chest and table east of its front, the field (3 by 3, water in the middle) behind them.
chest = at + (6, 0, 0)
table = at + (6, 0, 1)
field = (at + (6, -1, 2), at + (8, -1, 4))
water = at + (7, -1, 3)


def flatten():
    """Levels the plot, house, chest and field with a block round them, at ground, getting the dirt it falls short of."""
    low = at + (-1, -1 - DEEP, -1)
    high = at + (9, TALL - 1, 5)
    r = lib.terrain.flatten(low, high, ground)
    if r["reason"] == "missing_ingredients":
        g = get("dirt", r["missing"]["minecraft:dirt"], radius)
        if not g["ok"]:
            return g
        r = lib.terrain.flatten(low, high, ground)
    return r


def furnish():
    r = place(chest, "chest")
    return r if not r else place(table, "crafting_table")


def pour():
    """Pours a water source in the middle of the field, so its farmland stays wet, with a water bucket from lib.get."""
    if block(water).id == "minecraft:water":
        return
    # ponytail: no bucket in reach, no water (the field is planted dry); filling a bucket at a lake would cover that.
    if count("water_bucket") or get("water_bucket", 1, radius)["ok"]:
        mine(water)
        use(water, "water_bucket")  # aimed at the hole itself, so the water goes in, not over its rim


def plant():
    pour()
    spots = lib.farm.field(*field)
    r = ensure_tool("hoe", len(spots), None, radius)
    if not r["ok"]:
        return r
    if count(SEED) < len(spots):
        get(SEED, len(spots), radius)  # ponytail: a field short of seeds is planted as far as they go; tend says what lacked
    # tend gives blocked where soil is left untilled or a seed it still carries did not go in (the bot stood there, say):
    # the step tries once more (tries=2). Bare farmland with no water near turns back into dirt.
    return lib.farm.tend(field[0], field[1], SEED)


def home_in():
    set_home(at + (2, 1, 1))  # inside, behind the door, over the floor
    goto(home())


steps = [Step("kit", lib.bootstrap.kit, (KIT, radius)),
         Step("flatten", flatten),
         Step("hut", build_here, (hut, at, 0, False, radius)),
         Step("furnish", furnish),
         Step("field", plant, tries=2, optional=True),
         Step("store", lib.storage.deposit, (None, radius), optional=True),
         Step("home", home_in)]
r = lib.project.run("settle", steps)
if r["ok"]:
    # Settled: the next run settles anew.
    lib.project.reset("settle")
    memory.pop("settle_at")
say("settled" if r["ok"] else f"not settled: {r['reason']} at {r['at']}, left {r['missing']}")
