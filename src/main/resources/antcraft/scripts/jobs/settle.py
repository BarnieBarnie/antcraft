# A job: settles a new base from an empty-handed bot. Run it as /antcraft bot run jobs.settle [x y z [radius [hut]]],
# such as jobs.settle 10 64 10: the lowest corner of the house, by default where the bot stands (or where a stopped run
# started), how far to look for what it needs, in blocks (32 by default), and the house's blueprint (the bundled starter
# house by default). One project of lib.project, so a stopped run resumes at the step it was on: it gets a kit, levels
# the plot, builds the house, places a chest and a crafting table beside it, plants a small wheat field round a water
# source, stores the rest and sets home inside the house (docs/cookbook/settle.md). Another script runs it with
# run(at, radius, hut); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.bootstrap
import lib.farm
import lib.project
import lib.stock
import lib.storage
import lib.terrain
from lib.build import build_here
from lib.get import ensure_tool, get, make_cost
from lib.project import Step

HUT = "antcraft:starter_house"  # 5 by 4 by 5, a floor, its door at (2, 1, 0); any blueprint that fits in 5 by 5 will do
# Home is the hut's first work spot, or beside the chest where it has none.
# The tools first, then what is placed, and dirt to climb on while building.
KIT = [("stone_pickaxe", 1), ("stone_axe", 1), ("stone_shovel", 1), ("stone_hoe", 1), ("crafting_table", 1),
       ("chest", 1), ("dirt", 16)]
SEED = "minecraft:wheat_seeds"
TALL = 6  # how high over the ground the plot is cleared
DEEP = 3  # how deep a hole in the plot is filled

# The house at at, the chest and table east of its front, the field (3 by 3, water in the middle) behind them.
CHEST = (6, 0, 0)
TABLE = (6, 0, 1)
FIELD = ((6, -1, 2), (8, -1, 4))
WATER = (7, -1, 3)
# The most ticks an empty bucket may cost to make for the field: a chest's iron, walked to and smelted, not an ore dig.
BUCKET_TICKS = 1000


def flatten(at, radius):
    """Levels the plot, house, chest and field with a block round them, at ground, getting the dirt it falls short of."""
    low = at + (-1, -1 - DEEP, -1)
    high = at + (9, TALL - 1, 5)
    r = lib.terrain.flatten(low, high, at.y - 1)
    if r["reason"] == "missing_ingredients":
        g = get("dirt", r["missing"]["minecraft:dirt"], radius)
        if not g["ok"]:
            return g
        r = lib.terrain.flatten(low, high, at.y - 1)
    return r


def furnish(at):
    r = place(at + CHEST, "chest")
    return r if not r else place(at + TABLE, "crafting_table")


def pour(at, radius):
    """Pours a water source in the middle of the field, so its farmland stays wet, with a water bucket from lib.get, else
    an empty bucket from lib.get (a chest's, or made of three iron ingots) filled at the nearest water source (#592)."""
    water = at + WATER
    if block(water).id == "minecraft:water":
        return
    # With no water source within radius, or no bucket at hand, no bucket is got: the field is planted dry.
    if (count("water_bucket") or get("water_bucket", 1, radius)["ok"]
            or lib.farm.water(radius) is not None and bucket_at_hand(radius) and get("bucket", 1, radius)["ok"]
            and lib.farm.fill_bucket(radius)["ok"]):
        mine(water)
        use(water, "water_bucket")  # aimed at the hole itself, so the water goes in, not over its rim


def bucket_at_hand(radius):
    """Whether an empty bucket is carried, in a chest within radius, or made for at most BUCKET_TICKS (a chest's iron),
    so the optional field never digs for iron ore, thousands of ticks (#615)."""
    return (count("bucket") > 0 or len(lib.stock.where("minecraft:bucket", radius)) > 0
            or make_cost("bucket", 1, radius, None, BUCKET_TICKS) is not None)


def plant(at, radius):
    pour(at, radius)
    field = (at + FIELD[0], at + FIELD[1])
    spots = lib.farm.field(*field)
    r = ensure_tool("hoe", len(spots), None, radius)
    if not r["ok"]:
        return r
    if count(SEED) < len(spots):
        get(SEED, len(spots), radius)  # ponytail: a field short of seeds is planted as far as they go; tend says what lacked
    # tend gives blocked where soil is left untilled or a seed it still carries did not go in (the bot stood there, say):
    # the step tries once more (tries=2). Bare farmland with no water near turns back into dirt.
    return lib.farm.tend(field[0], field[1], SEED)


def home_in(at, hut):
    """Home at the house's first work spot (the starter house's is inside, behind the door), else beside the chest."""
    spots = blueprint(hut, at)["work_spots"]
    set_home(spots[0] if spots else at + (5, 0, 0))
    goto(home())


def run(at=None, radius=32, hut=HUT):
    """Settles a base with the house hut at at (where the stopped run started, else where the bot stands, where None).
    Gives lib.project.run's result()."""
    # Without at, a rerun resumes where the stopped run started, not where the bot stands now.
    at = at if at is not None else memory.get("settle_at", pos())
    memory["settle_at"] = at
    steps = [Step("kit", lib.bootstrap.kit, (KIT, radius)),
             Step("flatten", flatten, (at, radius)),
             Step("hut", build_here, (hut, at, 0, False, radius)),
             Step("furnish", furnish, (at,)),
             Step("field", plant, (at, radius), tries=2, optional=True),
             Step("store", lib.storage.deposit, (None, radius), optional=True),
             Step("home", home_in, (at, hut))]
    r = lib.project.run("settle", steps)
    if r["ok"]:
        # Settled: the next run settles anew.
        lib.project.reset("settle")
        memory.pop("settle_at")
    say("settled" if r["ok"] else f"not settled: {r['reason']} at {r['at']}, left {r['missing']}")
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) > 2 else None, int(args[3]) if len(args) > 3 else 32,
        args[4] if len(args) > 4 else HUT)
