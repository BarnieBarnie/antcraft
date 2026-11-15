# A job: grows a wood farm from nothing and farms it until a player stops it. Run it as
# /antcraft bot run jobs.wood_farm <kind> [x y z [cells [radius]]], such as jobs.wood_farm birch_sapling: a kind of
# lib.treefarm.KINDS (a sapling, or giant_spruce or giant_jungle), the farm's first corner, by default where the bot stands (or where a stopped run of the kind
# started), the most cells it grows to (by default most of the ticks a tree takes, learnt as it farms, else HARVEST[kind]) and how far to look for what it needs, in
# blocks (32 by default). One project of lib.project, so a stopped run resumes at the step it was on: it gets a kit, the saplings,
# builds the first cell (lib.treefarm on lib.cells), then farms forever: fells each grown tree and replants, stores the
# logs, sticks and apples in a depot (lib.depot) north of the farm, and builds the next cell once it has or can get
# what one takes (docs/cookbook/wood-farm.md). Its home is by the depot, so after a death it comes back to the farm and
# recovers (#679). Another job starts a farm with run(kind, at); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.area
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
from lib.result import result

# A sapling grows on random ticks: each tick randomTickSpeed (3) blocks of each 16x16x16 section (4096 blocks) get one,
# and SaplingBlock.randomTick advances a sapling on 1 in 7 of those (with light 9 or more over it), from stage 0 to 1,
# then from 1 to a tree. So a tree takes 2 * 7 * 4096 / 3 ticks on average, some 16 minutes.
GROWTH = 2 * 7 * 4096 / 3
# The ticks the bot takes for one tree of each kind: fell it, replant and store the logs. Birch is measured, some 150 to
# 1050 in WoodFarmGameTest, so 400 for its mean of 6 logs; the others are that per log times their mean logs from
# lib.treefarm.KINDS's trunks and branches: oak 5.5, spruce 6.5, jungle 8, acacia 9, cherry 13.5, dark and pale oak 34
# (four trunks and branches), poplar 15 (measured). The giants are measured in WoodFarmGameTest, once fell_whole took its
# scaffold back on its way down (#677): a giant spruce with a trunk of 24 took some 3000 to 3800 ticks, a giant jungle of
# 67 logs (a trunk of 16 and its branches) some 2100 to 2600 (#669).
# A farm learns its own (harvest, #653); these are its start.
HARVEST = {"minecraft:oak_sapling": 350, "minecraft:birch_sapling": 400, "minecraft:spruce_sapling": 450,
           "minecraft:jungle_sapling": 550, "minecraft:acacia_sapling": 600, "minecraft:cherry_sapling": 900,
           "minecraft:dark_oak_sapling": 2250, "minecraft:pale_oak_sapling": 2250, "minecraft:poplar_sapling": 1000,
           "giant_spruce": 3300, "giant_jungle": 2600}
SAMPLES = 3  # trees a farm fells before it sizes itself from its own ticks a tree rather than HARVEST
WINDOW = 10  # the last trees harvest is the mean of, so the first slow trees of a farm fade out (#662)
WAIT = 100  # ticks between looks at the farm while nothing has grown
# Rounds the farm lets pass before it tries again to get what the next cell takes, where it could not (#651): a get can
# be a long chain (charcoal, a furnace, cobblestone, a pickaxe). Some 1000 ticks of waits; a felling tries at once.
BACKOFF = 10
KIT = [("stone_axe", 1), ("dirt", 16)]  # dirt to climb a tall tree with and to put under the saplings; more for a giant (kit)
CATEGORIES = {"saplings": ["#minecraft:saplings"], "apples": ["minecraft:apple"], "logs": ["#minecraft:logs"],
              "sticks": ["minecraft:stick"]}
# What the farm keeps on the bot instead of storing it, besides its own saplings: what a cell takes and the crafting
# table lib.get makes a depot chest with.
KEEP = [lib.treefarm.DIRT, lib.treefarm.TORCH, lib.treefarm.SLABS, "minecraft:crafting_table"]


def most(harvest=HARVEST["minecraft:birch_sapling"]):
    """The cells at which the bot is always busy: a tree's mean growth ticks over the ticks it takes for one, harvest
    (HARVEST[kind] for a farm of kind); at least one."""
    return max(1, int(GROWTH // harvest))


def harvest(kind):
    """The ticks a tree of kind takes the bot: the mean of those memory["wood_ticks"] keeps for kind (learn), once it
    has SAMPLES trees, else HARVEST[kind]."""
    ticks = memory.get("wood_ticks", {}).get(kind, [])
    return sum(ticks) / len(ticks) if len(ticks) >= SAMPLES else HARVEST[kind]


def learn(kind, ticks, trees):
    """Adds trees felled of kind in ticks, game ticks, to memory["wood_ticks"]: for each kind the ticks of its last
    WINDOW trees, each of a round's trees its mean (#662)."""
    h = memory.get("wood_ticks", {})
    h[kind] = (h.get(kind, []) + [ticks / trees] * trees)[-WINDOW:]
    memory["wood_ticks"] = h


def limit(kind, size):
    """The most cells of a farm of kind: size, else most(harvest(kind))."""
    return size or most(harvest(kind))


def materials(kind):
    """What the farm wants on hand to build a cell of kind: lib.treefarm.materials and a spare sapling for each spot, for
    replanting."""
    m = lib.treefarm.materials(kind)
    s = lib.treefarm.sapling(kind)
    m[s] = m[s] * 2
    return m


def own():
    """Strikes what the bot borrowed off lib.borrow: the farm keeps what it took for good."""
    for b in lib.borrow.borrowed():
        lib.borrow.keep(b["item"])


def build(kind, name, size, radius):
    """Builds the next cell of the farm name with the tools lib.terrain.needs for its ground and for the ground under
    its sapling spots, marks it built and says so. Gives result()."""
    i = lib.cells.next(name)
    low, high = lib.cells.cell(name, i)
    tools = lib.terrain.needs(low, Pos(high.x, low.y + lib.treefarm.spec(kind)["clear"], high.z), low.y)["tools"]
    # build_cell mines the ground under each sapling spot that is not dirt to put dirt there, such as stone (#612).
    for p in lib.treefarm.spots(kind, low, high):
        if not lib.trees.is_kind(p.down(), "#minecraft:dirt"):
            for k, uses in lib.terrain.needs(p.down(), p.down(), p.y - 2)["tools"].items():
                tools[k] = tools.get(k, 0) + uses
    for k, uses in tools.items():
        r = ensure_tool(k, uses, None if k == "pickaxe" else "hand", radius)
        if not r["ok"]:
            return r
    lib.trees.take_back()  # what earlier walks placed comes down before the cell goes up, as before reap's harvest (#654)
    r = lib.treefarm.build_cell(kind, low, high)
    if r["ok"]:
        lib.cells.add(name, i)
        say(f"built cell {i + 1}")
        say(f"cells {lib.cells.count(name)}/{limit(kind, size)}")
    return r


def gets(item, n, radius):
    """get(item, n, radius), but the roof's slabs (lib.treefarm.SLABS, a tag) are any wooden slabs the chests within
    radius hold (lib.stock.take_up_to takes a tag), and those they lack are made as oak slabs (#612). Gives result()."""
    if item != lib.treefarm.SLABS:
        return get(item, n, radius)
    if lib.stock.take_up_to(item, n, radius)["ok"]:
        return result()
    # ponytail: lib.get takes an item, not a tag; oak is the one kind of slab it makes, a tag-aware get would make any.
    return get("minecraft:oak_slab", count("minecraft:oak_slab") + n - count(item), radius)


def first_cell(kind, name, size, radius):
    """Gets what the first cell takes (gets) and builds it. Gives result()."""
    for item, n in lib.treefarm.materials(kind).items():
        r = gets(item, n, radius)
        if not r["ok"]:
            return r
    return build(kind, name, size, radius)


def stocked(kind, radius):
    """Makes the bot carry materials(kind) by gets, as first_cell does (#643): what it lacks from the chests within
    radius, else torches crafted, dirt dug, saplings from leaves, oak slabs for the roof. Gives result(): ok, or the
    failed get's for the first item it could not get."""
    for item, n in materials(kind).items():
        r = gets(item, n, radius)
        if not r["ok"]:
            return r
    return result()


def sweep(kind, boxes):
    """Picks up the saplings of kind, sticks and apples lying in the farm, its cells boxes up to their clear height:
    leaves left after a felling decay over the next minutes and drop them then (#643)."""
    what = [lib.treefarm.sapling(kind)] + lib.treefarm.DROPS
    corners = []
    for b in boxes:
        corners += list(b)
    low, high = lib.area.bounds(corners, 1)
    high = high.up(lib.treefarm.spec(kind)["clear"])
    # ponytail: at most a walk a cell; a drop pick_up cannot reach stays for the next wait.
    for _ in boxes:
        near = [e for e in items_near(what, 64) if lib.area.inside(e.pos, low, high)]
        if not near:
            return
        goto(near[0].pos)
        if not pick_up(what).value:
            return


def unplanted(kind, b):
    """The spots of cell b, a (low, high) box, that hold neither a sapling of kind nor a log."""
    return [p for p in lib.treefarm.spots(kind, b[0], b[1]) if block(p).id != lib.treefarm.sapling(kind) and not lib.trees.is_log(p)]


def tend(kind, b):
    """Puts back what cell b lacks: a sapling on each spot without one (mining what stands there first, such as a
    scaffold block a felling left) and its torches (a walk that pillars where a torch stands breaks it, and the bot
    picks it up), as far as the bot has them. Nothing while logs of the cell's tree stand, as a sapling under them never
    grows (#644)."""
    if lib.treefarm.grown(kind, b[0], b[1]):
        return
    torches = lib.treefarm.torches(b[0], b[1])
    s = lib.treefarm.sapling(kind)
    for p in unplanted(kind, b):
        if count(s):
            if not block(p).is_air:
                mine(p)
            place(p, s)
    for t in torches:
        if block(t).id != lib.treefarm.TORCH and count(lib.treefarm.TORCH):
            if not block(t).is_air:
                mine(t)
            place(t, lib.treefarm.TORCH)


def tell(told, what):
    """Says what once: not where told, a dict of what was said, holds it already."""
    if what not in told:
        told[what] = True
        say(what)


def scaffold(kind):
    """The dirt the bot carries to pillar up a tree of kind with: the kit's 16, or the cell's clear height where that is
    more, as a giant's trunk of up to 29 (#658)."""
    return max(dict(KIT)["dirt"], lib.treefarm.spec(kind)["clear"])


def kit(kind):
    """KIT for a farm of kind: its dirt scaffold(kind)."""
    return [(item, scaffold(kind) if item == "dirt" else n) for item, n in KIT]


def reap(kind, b, radius, told):
    """Fells the tree of cell b with an axe (lib.treefarm.harvest). Where that leaves a log, says why once (tell), gets
    scaffold(kind) dirt to pillar with and an axe, and harvests again (#644). Gives the last harvest's result()."""
    ensure_tool("axe", 16, "hand", radius)
    # What earlier walks placed comes down first, out of the felling's way: harvest leaves it standing, listed (#654, #657).
    lib.trees.take_back()
    r = lib.treefarm.harvest(kind, b[0], b[1], radius)
    if not r["ok"]:
        tell(told, f"harvest: {r['reason']} at {r['at']}")
        get(lib.treefarm.DIRT, scaffold(kind), radius)
        ensure_tool("axe", 16, "hand", radius)
        lib.trees.take_back()
        r = lib.treefarm.harvest(kind, b[0], b[1], radius)
    return r


def farm(kind, name, size, radius):
    """Farms forever: each round fells every grown tree (reap, so logs a failed felling left are felled again), stores
    what it fell, tends each cell (replants it with saplings from the chests, else from the leaves of the kind with
    lib.get), builds the next cell where the farm is below size and stocked gets what one takes (else says once what it
    lacks, and tries again BACKOFF rounds later, or in the next round that felled a tree, whose logs may make it), and
    where it felled nothing picks up the drops in the farm (sweep) and waits. Each round takes back the scaffold its walks
    placed (lib.trees.take_back) before it waits, and before each felling and build (#654). Where size is None the farm grows to
    limit(kind, None), as its rounds learn the ticks a tree takes: from the felling to the replanting, over the trees
    felled (learn)."""
    full = False
    told = {}
    backoff = 0  # rounds until stocked is tried again
    while True:
        # What the setup steps' walks placed is not the round's; what the round's walks place (the felling's and the
        # build's own taken back by them, sweep, tend, get, reap's retry) comes down at its end (#654).
        placed_scaffold()
        own()
        boxes = [lib.cells.cell(name, i) for i in lib.cells.built(name)]
        grown = [b for b in boxes if lib.treefarm.grown(kind, b[0], b[1])]
        start = game_time()
        trees = 0
        for b in grown:
            if reap(kind, b, radius, told)["ok"]:
                trees += 1
        felled = trees > 0
        if grown:
            r = lib.depot.store(name, KEEP + [lib.treefarm.sapling(kind)], radius)
            if not r["ok"]:
                say(f"store: {r['reason']}, kept {r['missing']}")
        # After the felling, so lib.get breaks the felled trees' leaves for saplings, not those of a tree still to fell.
        n = sum([len(unplanted(kind, b)) for b in boxes])
        s = lib.treefarm.sapling(kind)
        if n and count(s) < n and not lib.stock.take_up_to(s, n, radius)["ok"]:
            get(s, n, radius)
        for b in boxes:
            tend(kind, b)
        if felled:
            learn(kind, game_time() - start, trees)
            backoff = 0
        most_cells = limit(kind, size)
        if full and lib.cells.count(name) < most_cells:
            say(f"cells {lib.cells.count(name)}/{most_cells}")  # it learnt it may grow (#662); "farm full" again once it is
            full = False
        if lib.cells.count(name) < most_cells and backoff:
            backoff -= 1
        elif lib.cells.count(name) < most_cells:
            r = stocked(kind, radius)
            if r["ok"]:
                build(kind, name, size, radius)
            else:
                tell(told, f"next cell: {r['reason']}, lacks {r['missing']}")
                backoff = BACKOFF
        elif not full:
            say("farm full")
            full = True
        if not felled:
            sweep(kind, boxes)
        lib.trees.take_back()
        if not felled:
            wait(WAIT)


def recover():
    """After a death, which the restarted run sees as deaths() above memory["wood_deaths"], takes back the scaffold the
    run it died in left (the pillar it died on first) and picks up what it dropped round last_death() (#679). The bot
    respawned at the farm, its home. On a first run it only notes deaths()."""
    if deaths() <= memory.get("wood_deaths", deaths()):
        memory["wood_deaths"] = deaths()
        return
    memory["wood_deaths"] = deaths()
    lib.trees.take_back()
    p = last_death()
    goto(Pos(p.x, pos().y, p.z), near=2)
    pick_up(radius=8)


def run(kind, at=None, size=None, radius=32):
    """Grows and farms a wood farm of kind (a kind of lib.treefarm.KINDS) with its first corner at at (where the bot
    stands, or where the last farm of kind started), up to size cells (limit: most of the ticks a tree takes, learnt,
    else HARVEST[kind]). Never returns but on a
    failed setup step: gives lib.project.run's result() then."""
    kind = lib.treefarm.key(kind)
    name = kind.split(":")[-1].replace("_sapling", "") + "_farm"
    farms = memory.get("wood_farms", {})
    at = Pos(at) if at is not None else farms.get(kind, pos())
    farms[kind] = at
    memory["wood_farms"] = farms
    w = lib.treefarm.spec(kind)["size"]
    lib.cells.layout(name, at.down(), (w, w))
    # The depot north of the farm, a path's width off its first row, going east along it.
    lib.depot.depot(name, at + (0, 0, -2), "east", CATEGORIES)
    # Home is the path by the depot, so a bot that dies comes back at the farm, and recover fetches what it dropped: a
    # death costs it no more than lib.cost.death (#679).
    set_home(at + (0, 0, -1))
    recover()
    n = 4 if lib.treefarm.spec(kind)["square"] else 1
    steps = [Step("kit", lib.bootstrap.kit, (kit(kind), radius)),
             Step("sapling", get, (lib.treefarm.sapling(kind), 2 * n, radius)),
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
