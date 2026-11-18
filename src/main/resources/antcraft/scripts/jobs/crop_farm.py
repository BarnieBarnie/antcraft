# A job: grows a crop farm from a cell's worth of seed and farms it until a player stops it. Run it as
# /antcraft bot run jobs.crop_farm <crop> [x y z [cells [radius]]], such as jobs.crop_farm carrot: a crop, seed or harvest
# of lib.farm.CROPS (wheat, carrot, potato, beetroot), the farm's first corner (by default where the bot stands, or where
# a stopped run of the crop started), the most cells it grows to (by default most of the ticks a cell takes to ripen,
# no more than the cells that get random ticks round its corner) and how far to look for what it needs (32 by default).
# Each cell is 9x9 soil round one water source in its middle, which wets all of it: levelled (lib.terrain.flatten),
# watered from a bucket (jobs.farm.pour), tilled and planted (lib.farm.tend). Each round harvests every ripe cell and
# replants it, stores the harvest in a depot north of the farm, and builds the next cell once the bot and the chests
# hold a cell's worth of seed beyond what it keeps (docs/cookbook/crop-farm.md). The farm's layout, depot, growth and
# rounds are lib.cellfarm's (#826); this module is its crop spec (spec). Another job starts a farm with run(crop, at);
# imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import jobs.farm
import lib.cellfarm
import lib.cells
import lib.farm
import lib.stock
import lib.terrain
import lib.treefarm
from lib.cellfarm import tell
from lib.get import ensure_tool, get
from lib.project import Step
from lib.result import result

SIZE = 9  # a cell's width and depth: one water source wets 4 blocks round it
CELL = SIZE * SIZE - 1  # the seeds a cell takes: all but the water
# A crop grows a stage on 1 in 6 of its random ticks (CropBlock.getGrowthSpeed 5 on hydrated farmland among rows of
# the same crop), and each block of a section gets one on 3 of 4096 ticks: so a stage takes 6 * 4096 / 3 ticks, some 7
# minutes, and a crop its ripe age's stages.
STAGE = 6 * 4096 / 3
# The ticks the bot takes to harvest and replant a cell, measured once in CropFarmGameTest (a wheat cell): its guess
# till it learns its own (lib.cellfarm.most, #844).
HARVEST = 600
ROOM = 6  # empty slots before a harvest: some 290 carrots or potatoes of a cell, with poisonous ones, or wheat and seeds


def middle(low, high):
    """The cell's water: the middle block of its floor."""
    return low + ((high.x - low.x) // 2, 0, (high.z - low.z) // 2)


def waters(name):
    """The water sources of the farm name's built cells: a bucket is never filled at one (jobs.farm.pour's keep)."""
    return [middle(*lib.cells.cell(name, i)) for i in lib.cells.built(name)]


def tend_cell(c, name, low, high, radius):
    """Pours the cell's water where it is gone (pour, never filling at the farm's own), gets a hoe for its soil to till
    and the seeds of crop c its bare spots take, then harvests what is ripe, tills and plants (lib.farm.tend). Gives
    lib.farm.tend's result(), or the failed hoe's."""
    mid = middle(low, high)
    if block(mid).id != lib.farm.WATER:
        jobs.farm.pour(mid, radius, waters(name))
    spots = lib.farm.field(low, high)
    till = len([s for s in spots if block(s).id in lib.farm.SOIL and block(s.up()).is_air])
    if till:
        r = ensure_tool("hoe", till, None, radius)
        if not r["ok"]:
            return r
    bare = len([s for s in spots if block(s.up()).is_air])
    if bare and count(c[1]) < bare:
        get(c[1], bare, radius)  # ponytail: short of seed, the cell is planted as far as it goes; tend says the rest
    return lib.farm.tend(low, high, c[1])


def build_cell(c, name, low, high, radius):
    """Builds the cell: levels its floor (lib.terrain.flatten, clearing the block over it), puts dirt where its floor is
    not soil, such as stone (lib.treefarm.soil), pours the water in its middle, then tills and plants it (tend_cell).
    Gives result(): ok, no_water: what pour lacked (jobs.farm.lacks) at the middle where no water could be poured, or the reason of the step that failed."""
    r = lib.terrain.flatten(low, Pos(high.x, low.y + 1, high.z), low.y)
    if not r["ok"]:
        return r
    mid = middle(low, high)
    for x in range(low.x, high.x + 1):
        for z in range(low.z, high.z + 1):
            p = Pos(x, low.y, z, low.dimension)
            if p != mid and block(p).id not in lib.farm.SOIL + [lib.farm.FARMLAND]:
                r = lib.treefarm.soil(p, radius)
                if not r["ok"]:
                    return r
    if not jobs.farm.pour(mid, radius, waters(name)):
        return result(f"no_water: {jobs.farm.lacks(radius, waters(name))}", mid)
    return tend_cell(c, name, low, high, radius)


def ripe(low, high):
    """Whether the cell has crops and all of them are ripe, as jobs.farm waits for."""
    crops = [s.up() for s in lib.farm.field(low, high) if not block(s.up()).is_air]
    return len(crops) > 0 and all([lib.farm.ripe(p) for p in crops])


def lacking(low, high):
    """Whether the cell lacks its water or a crop on a spot."""
    return (block(middle(low, high)).id != lib.farm.WATER
            or any([block(s.up()).is_air for s in lib.farm.field(low, high)]))


def harvest(c, name, low, high, radius, told):
    """Harvests and replants the cell (tend_cell), saying once what it could not. Gives result(): ok, as the ripe crops
    are taken either way."""
    r = tend_cell(c, name, low, high, radius)
    if not r["ok"]:
        tell(told, f"harvest: {r['reason']} at {r['at']}")
    return result()


def tend(c, name, boxes, radius, told):
    """Tends each cell of boxes that lacks its water or a crop (lacking), saying once what it could not. A cell with its
    water is left where there is no seed of crop c to plant, none carried and none in the chests within radius: it was
    walked to every round for nothing (#844)."""
    seeded = None  # whether there is seed, looked up once
    for b in boxes:
        if not lacking(b[0], b[1]):
            continue
        if block(middle(b[0], b[1])).id == lib.farm.WATER:
            if seeded is None:
                seeded = count(c[1]) > 0 or len(lib.stock.where(c[1], radius)) > 0
            if not seeded:
                continue
        r = tend_cell(c, name, b[0], b[1], radius)
        if not r["ok"]:
            tell(told, f"tend: {r['reason']} at {r['at']}")


def most(c, name):
    """The cells of the farm name at which the bot is always busy: crop c's ripening ticks (its ripe age's stages) over
    the ticks a harvest takes, learnt as it farms, else HARVEST (lib.cellfarm.most)."""
    return lib.cellfarm.most(name, c[3] * STAGE, HARVEST)


def spec(c, name, radius):
    """The lib.cellfarm spec of the crop farm name of crop c (a lib.farm.CROPS entry): 9x9 cells round a water source,
    harvested when all their crops are ripe, the harvest stored in a crops chest, the bot keeping a cell's seed."""
    return {"size": (SIZE, SIZE), "categories": {"crops": [c[1], c[2], lib.farm.POISON]}, "corners": "crop_farms",
            "kept": lib.cellfarm.floor,
            "keep": [(c[1], CELL), "#minecraft:hoes", lib.farm.BUCKET, "minecraft:water_bucket", "minecraft:crafting_table"],
            "materials": lambda: {c[1]: CELL}, "gets": lib.treefarm.get_outside,
            "build_cell": lambda low, high: build_cell(c, name, low, high, radius),
            "ripe": ripe, "room": lambda: ROOM,
            "harvest": lambda low, high, told: harvest(c, name, low, high, radius, told),
            "tend": lambda boxes, told: tend(c, name, boxes, radius, told),
            "most": lambda: most(c, name)}


def first_cell(c, name, size, radius):
    """Gets a cell's seed (lib.cellfarm.stocked) and builds the first cell. Gives result()."""
    f = spec(c, name, radius)
    r = lib.cellfarm.stocked(f, radius)
    if not r["ok"]:
        return r
    return lib.cellfarm.build(f, name, size, radius)


def farm(c, name, size, radius):
    """Farms the crop farm name forever (lib.cellfarm.farm on spec)."""
    return lib.cellfarm.farm(spec(c, name, radius), name, size, radius)


def run(crop, at=None, size=None, radius=32):
    """Grows and farms a crop farm of crop (a crop, seed or harvest of lib.farm.CROPS) with its first corner at at (where
    the bot stands, or where the last farm of crop started), up to size cells (else lib.cellfarm.limit). Never returns
    but on a failed setup step, or no such crop: gives lib.project.run's result() then, None for no crop."""
    c = lib.farm.crop(crop)
    if c is None:
        warn("crop_farm: no crop", crop)
        return None
    name = c[0].split(":")[-1] + "_farm"
    steps = [Step("cell", first_cell, (c, name, size, radius)), Step("farm", farm, (c, name, size, radius))]
    return lib.cellfarm.run(spec(c, name, radius), name, c[0], at, steps, radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(args[0], Pos(int(args[1]), int(args[2]), int(args[3])) if len(args) > 3 else None,
        int(args[4]) if len(args) > 4 else None, int(args[5]) if len(args) > 5 else 32)
