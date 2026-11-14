# A job: grows a wood farm from nothing and farms it until a player stops it. Run it as
# /antcraft bot run jobs.wood_farm <sapling> [x y z [cells [radius]]], such as jobs.wood_farm birch_sapling: a sapling of
# lib.treefarm.KINDS, the farm's first corner, by default where the bot stands (or where a stopped run of the kind
# started), the most cells it grows to (most() by default) and how far to look for what it needs, in blocks (32 by
# default). One project of lib.project, so a stopped run resumes at the step it was on: it gets a kit, the saplings,
# builds the first cell (lib.treefarm on lib.cells), then farms forever: fells each grown tree and replants, stores the
# logs, sticks and apples in a depot (lib.depot) north of the farm, and builds the next cell once the chests hold what
# one takes (docs/cookbook/wood-farm.md). Another job starts a farm with run(kind, at); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.bootstrap
import lib.borrow
import lib.cells
import lib.depot
import lib.project
import lib.stock
import lib.terrain
import lib.treefarm
import lib.trees
from lib.get import ensure_tool, get
from lib.project import Step

# A sapling grows on random ticks: each tick randomTickSpeed (3) blocks of each 16x16x16 section (4096 blocks) get one,
# and SaplingBlock.randomTick advances a sapling on 1 in 7 of those (with light 9 or more over it), from stage 0 to 1,
# then from 1 to a tree. So a tree takes 2 * 7 * 4096 / 3 ticks on average, some 16 minutes.
GROWTH = 2 * 7 * 4096 / 3
# The ticks the bot takes for one tree: fell a birch, replant and store the logs, some 150 to 1050 in WoodFarmGameTest.
HARVEST = 400
WAIT = 100  # ticks between looks at the farm while nothing has grown
KIT = [("stone_axe", 1), ("dirt", 16)]  # dirt to climb a tall tree with and to put under the saplings
CATEGORIES = {"saplings": ["#minecraft:saplings"], "apples": ["minecraft:apple"], "logs": ["#minecraft:logs"],
              "sticks": ["minecraft:stick"]}
# What the farm keeps on the bot instead of storing it, besides its own saplings: what a cell takes and the crafting
# table lib.get makes a depot chest with.
KEEP = [lib.treefarm.DIRT, lib.treefarm.TORCH, lib.treefarm.SLABS, "minecraft:crafting_table"]


def most(harvest=HARVEST):
    """The cells at which the bot is always busy: a tree's mean growth ticks over the ticks it takes for one; at least
    one."""
    return max(1, int(GROWTH // harvest))


def materials(kind):
    """What the farm wants on hand to build a cell of kind: lib.treefarm.materials and a spare sapling for each spot, for
    replanting."""
    m = lib.treefarm.materials(kind)
    m[kind] = m[kind] * 2
    return m


def own():
    """Strikes what the bot borrowed off lib.borrow: the farm keeps what it took for good."""
    for b in lib.borrow.borrowed():
        lib.borrow.keep(b["item"])


def build(kind, name, size, radius):
    """Builds the next cell of the farm name with the tools lib.terrain.needs for its ground, marks it built and says so.
    Gives result()."""
    i = lib.cells.next(name)
    low, high = lib.cells.cell(name, i)
    n = lib.terrain.needs(low, Pos(high.x, low.y + lib.treefarm.spec(kind)["clear"], high.z), low.y)
    for k, uses in n["tools"].items():
        r = ensure_tool(k, uses, None if k == "pickaxe" else "hand", radius)
        if not r["ok"]:
            return r
    r = lib.treefarm.build_cell(kind, low, high)
    if r["ok"]:
        lib.cells.add(name, i)
        say(f"built cell {i + 1}")
        say(f"cells {lib.cells.count(name)}/{size}")
    return r


def first_cell(kind, name, size, radius):
    """Gets what the first cell takes with lib.get and builds it. Gives result()."""
    for item, n in lib.treefarm.materials(kind).items():
        # ponytail: lib.get takes an item, not a tag, so the roof is of oak slabs; any wooden slabs in a chest do later.
        r = get("minecraft:oak_slab" if item == lib.treefarm.SLABS else item, n, radius)
        if not r["ok"]:
            return r
    return build(kind, name, size, radius)


def stocked(kind, radius):
    """Whether the bot carries materials(kind), taking what it lacks from the chests within radius."""
    for item, n in materials(kind).items():
        if count(item) < n and not lib.stock.take_up_to(item, n, radius)["ok"]:
            return False
    return True


def unplanted(kind, b):
    """The spots of cell b, a (low, high) box, that hold neither a sapling of kind nor a log."""
    return [p for p in lib.treefarm.spots(kind, b[0], b[1]) if block(p).id != kind and not lib.trees.is_log(p)]


def tend(kind, b, radius):
    """Puts back what cell b lacks: a sapling on each spot without one (mining what stands there first, such as a
    scaffold block a felling left) and its torches (taken from the chests within radius where the bot carries too few),
    as far as the bot has them. ponytail: a walk that pillars where a torch stands puts its block there without
    dropping the torch, so a lost torch comes from the chests."""
    torches = lib.treefarm.torches(b[0], b[1])
    lost = len([t for t in torches if block(t).id != lib.treefarm.TORCH])
    if lost and count(lib.treefarm.TORCH) < lost:
        lib.stock.take_up_to(lib.treefarm.TORCH, lost, radius)
    for p in unplanted(kind, b):
        if count(kind):
            if not block(p).is_air:
                mine(p)
            place(p, kind)
    for t in torches:
        if block(t).id != lib.treefarm.TORCH and count(lib.treefarm.TORCH):
            if not block(t).is_air:
                mine(t)
            place(t, lib.treefarm.TORCH)


def farm(kind, name, size, radius):
    """Farms forever: each round fells every grown tree, stores what it fell, tends each cell (replants it with saplings
    from the chests, else from the leaves of the kind with lib.get), builds the next cell where the farm is below size
    and the chests hold what one takes, and waits where nothing had grown."""
    full = False
    while True:
        own()
        boxes = [lib.cells.cell(name, i) for i in lib.cells.built(name)]
        grown = [b for b in boxes if lib.treefarm.grown(kind, b[0], b[1])]
        for b in grown:
            ensure_tool("axe", 16, "hand", radius)
            lib.treefarm.harvest(kind, b[0], b[1])
        if grown:
            r = lib.depot.store(name, KEEP + [kind], radius)
            if not r["ok"]:
                say(f"store: {r['reason']}, kept {r['missing']}")
        # After the felling, so lib.get breaks the felled trees' leaves for saplings, not those of a tree still to fell.
        n = sum([len(unplanted(kind, b)) for b in boxes])
        if n and count(kind) < n and not lib.stock.take_up_to(kind, n, radius)["ok"]:
            get(kind, n, radius)
        for b in boxes:
            tend(kind, b, radius)
        if lib.cells.count(name) < size:
            if stocked(kind, radius):
                build(kind, name, size, radius)
        elif not full:
            say("farm full")
            full = True
        if not grown:
            wait(WAIT)


def run(kind, at=None, size=None, radius=32):
    """Grows and farms a wood farm of kind (a sapling of lib.treefarm.KINDS) with its first corner at at (where the bot
    stands, or where the last farm of kind started), up to size cells (most()). Never returns but on a failed setup step:
    gives lib.project.run's result() then."""
    kind = kind if ":" in kind else "minecraft:" + kind
    name = kind.split(":")[1].replace("_sapling", "") + "_farm"
    farms = memory.get("wood_farms", {})
    at = Pos(at) if at is not None else farms.get(kind, pos())
    farms[kind] = at
    memory["wood_farms"] = farms
    size = size or most()
    w = lib.treefarm.spec(kind)["size"]
    lib.cells.layout(name, at.down(), (w, w))
    # The depot north of the farm, a path's width off its first row, going east along it.
    lib.depot.depot(name, at + (0, 0, -2), "east", CATEGORIES)
    n = 4 if lib.treefarm.spec(kind)["square"] else 1
    steps = [Step("kit", lib.bootstrap.kit, (KIT, radius)),
             Step("sapling", get, (kind, 2 * n, radius)),
             Step("cell", first_cell, (kind, name, size, radius)),
             Step("farm", farm, (kind, name, size, radius))]
    r = lib.project.run(name, steps)
    say(f"{name} stopped: {r['reason']} at {r['at']}")
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(args[0], Pos(int(args[1]), int(args[2]), int(args[3])) if len(args) > 3 else None,
        int(args[4]) if len(args) > 4 else None, int(args[5]) if len(args) > 5 else 32)
