# A job: grows a wood farm from nothing and farms it until a player stops it. Run it as
# /antcraft bot run jobs.wood_farm <kind> [x y z [cells [radius]]], such as jobs.wood_farm birch_sapling: a kind of
# lib.treefarm.KINDS (a sapling, mangrove_propagule, or giant_spruce or giant_jungle), the farm's first corner, by default where the bot stands (or where a stopped run of the kind
# started), the most cells it grows to (by default most of the ticks a tree takes, learnt as it farms, else HARVEST[kind], and no
# more than the cells that get random ticks round its corner, #812) and how far to look for what it needs, in
# blocks (32 by default). One project of lib.project, so a stopped run resumes at the step it was on: it gets a kit, the saplings,
# builds the first cell (lib.treefarm on lib.cells), then farms forever: fells each grown tree and replants, stores the
# logs, sticks and apples in a depot (lib.depot) north of the farm, and builds the next cell once it has or can get
# what one takes (docs/cookbook/wood-farm.md). A spruce or jungle farm farms giants (GIANTS) once the bot has 4 saplings
# of the kind, from the start or as a farm of its own beside the small one, which fells the small cells' trees and leaves
# them unplanted (#803, #807). A full farm composts its extra saplings
# into bone meal for its saplings (compost, #804), and uses the bone meal stored in its depot on them (#809). Its home is by the depot, so after a death it comes back to the farm and
# recovers (lib.trees.recover, #679). Another job starts a farm with run(kind, at); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.area
import lib.bootstrap
import lib.borrow
import lib.cells
import lib.depot
import lib.food
import lib.project
import lib.stock
import lib.terrain
import lib.treefarm
import lib.trees
from lib.get import chest_held, ensure_tool, get
from lib.project import Step
from lib.result import result

# A sapling grows on random ticks: each tick randomTickSpeed (3) blocks of each 16x16x16 section (4096 blocks) get one,
# and SaplingBlock.randomTick advances a sapling on 1 in 7 of those (with light 9 or more over it), from stage 0 to 1,
# then from 1 to a tree. So a tree takes 2 * 7 * 4096 / 3 ticks on average, some 16 minutes.
GROWTH = 2 * 7 * 4096 / 3
# The ticks the bot takes for one tree of each kind: fell it, replant and store the logs. The one-sapling kinds and dark
# and pale oak are measured as the mangrove's farm test, 11 seeded trees each (WoodFarmGameTest
# eachKindsHarvestAndLogsAreMeasured, #769, birch #779): oak 165 to 302, 207 on average, birch 176 to 323, 237, spruce
# 250 to 433, 329, jungle 176 to 539, 359, acacia 207 to 464, 286, cherry 378 to 1231, 865, pale oak 663 to 1209, 941,
# poplar 378 to 1021, 613, dark oak 665 to 1251, 943; each the mean rounded to 50. Dark oak grows the same trees as
# pale oak, a seed for seed; it measured 1236 only while the game test data pack made every dark oak leaves block drop an
# apple for harvest to pick up (#779), measured again since they drop none (#790, #795).
# The giants are measured in WoodFarmGameTest, once fell_whole took its scaffold back on its way down (#677): a giant spruce with a trunk of 24 took some 3200 to 3500 ticks, once a path
# no longer stood on a block it broke on the way (#687; 4100 to 4700 with the bot falling off such a step), a giant jungle of
# 67 logs (a trunk of 16 and its branches) some 2100 to 3300, 2700 on average (#669, #695); since #713 the spruce 3300 to
# 3550, the jungle 1950 to 2750, 2700 to 3400 with the bot killed up the tree, 2700 the mean of both (#715); since #752
# the spruce 3050 to 3300, 3200 on average, the jungle 2000 to 2950, 2250 on average, its mean with no death (2300 to
# 3400 killed up the tree, 3750 with 36 giants felled at once, which its test checks on its own, #761, #769). A mangrove
# of 16 logs on average (measured over 188 trees) felled and its roots mined took some 250 to 3550 ticks in 39 seeded
# trees of TreeFarmGameTest, 1400 on average (#650); since harvest also mines roots up to 2 out of the cell (#768), 437 to
# 3123, 1724 on average, in 25 trees of 8 seeds felled as the mangrove's farm test (#782), so 1700.
# A farm learns its own (harvest, #653); these are its start.
HARVEST = {"minecraft:oak_sapling": 200, "minecraft:birch_sapling": 250, "minecraft:spruce_sapling": 350,
           "minecraft:jungle_sapling": 350, "minecraft:acacia_sapling": 300, "minecraft:cherry_sapling": 850,
           "minecraft:dark_oak_sapling": 950, "minecraft:pale_oak_sapling": 950, "minecraft:poplar_sapling": 600,
           "minecraft:mangrove_propagule": 1700, "giant_spruce": 3200, "giant_jungle": 2250}
SAMPLES = 3  # trees a farm fells before it sizes itself from its own ticks a tree rather than HARVEST
WINDOW = 10  # the last trees harvest is the mean of, so the first slow trees of a farm fade out (#662)
WAIT = 100  # ticks between looks at the farm while nothing has grown
# Rounds the farm lets pass before it tries again to get what the next cell takes, where it could not (#651): a get can
# be a long chain (charcoal, a furnace, cobblestone, a pickaxe). Some 1000 ticks of waits; a felling tries at once.
BACKOFF = 10
KIT = [("stone_axe", 1), (lib.treefarm.DIRT, 16)]  # dirt to climb a tall tree with and to put under the saplings; more for a giant (kit)
# A mangrove's roots and moss carpet, which harvest mines too (#768), go with the logs, not in a chest of their own (#788).
CATEGORIES = {"saplings": ["#minecraft:saplings"], "apples": ["minecraft:apple"],
              "logs": ["#minecraft:logs", "minecraft:mangrove_roots", "minecraft:muddy_mangrove_roots", "minecraft:moss_carpet"],
              "sticks": ["minecraft:stick"], "bone_meal": ["minecraft:bone_meal"]}
# What the farm keeps on the bot instead of storing it, besides its own saplings and its dirt (dirt): what a cell takes and
# the crafting table lib.get makes a depot chest with.
KEEP = [lib.treefarm.TORCH, lib.treefarm.SLABS, "minecraft:crafting_table"]
STACK = 64  # the dirt the farm keeps at least: a stack, for the fill of an uneven cell (dirt)
# The mean logs of a tree of each kind: poplar over 200 grown trees and mangrove over 188 (#650), the others the 11
# seeded trees HARVEST's comment measures, rounded to a half (oak 4.9, spruce 6.4, jungle 8.7, acacia 7.9, cherry 15.9,
# dark and pale oak 40.6, #769; birch 5.4, 5 to 7, #779); a giant spruce's trunk of 24 is 96 (#766).
LOGS = {"minecraft:oak_sapling": 5, "minecraft:birch_sapling": 5.5, "minecraft:spruce_sapling": 6.5,
        "minecraft:jungle_sapling": 8.5, "minecraft:acacia_sapling": 8, "minecraft:cherry_sapling": 16,
        "minecraft:dark_oak_sapling": 40.5, "minecraft:pale_oak_sapling": 40.5, "minecraft:poplar_sapling": 15,
        "minecraft:mangrove_propagule": 16, "giant_spruce": 96, "giant_jungle": 67}
OTHER = 4  # empty slots for a felling's saplings, sticks, apples and the dirt it takes back
ROOTS = 2  # and for a mangrove's mangrove roots and muddy mangrove roots, which harvest mines too
# The giant a one-sapling kind grows from 4 of its saplings: a farm of the kind farms it once it has them (#803).
GIANTS = {"minecraft:spruce_sapling": "giant_spruce", "minecraft:jungle_sapling": "giant_jungle"}


def giant(kind, radius):
    """The kind a farm of kind grows (#803): its giant (GIANTS) where a farm of the giant was started, or no farm of kind
    was and the bot can have the 4 saplings a giant cell takes (fours); else kind."""
    g = GIANTS.get(kind)
    farms = memory.get("wood_farms", {})
    return g if g and (g in farms or kind not in farms and fours(kind, radius)) else kind


def fours(kind, radius):
    """Whether the bot carries and the chests within radius hold the 4 saplings of kind a giant cell takes."""
    return count(kind) + chest_held(kind, radius)[1] >= 4


def beside(name):
    """The first corner of a farm south of the rows of the farm name's cells built, its depot a path's width off them."""
    row = lib.cells.farm(name)["row"]
    return lib.cells.cell(name, (lib.cells.built(name)[-1] // row + 1) * row)[0] + (0, 1, 2)


def small(kind):
    """The one-sapling kind and the grown cells, (low, high) boxes, of the small farm a giant farm of kind moved from
    (#807); none where it moved from none. Its trees are felled by the giant farm's rounds and not replanted."""
    for k, g in GIANTS.items():
        if g == kind and k in memory.get("wood_farms", {}):
            name = k.split(":")[-1].replace("_sapling", "") + "_farm"
            boxes = [lib.cells.cell(name, i) for i in lib.cells.built(name)]
            return k, [b for b in boxes if lib.treefarm.grown(k, b[0], b[1])]
    return None, []


def saplings(kind, n, radius):
    """get(sapling, 2 * n, radius): a spare for each of a cell's n spots; ok with the n the first cell takes where no
    more can be had (#803). Gives result()."""
    s = lib.treefarm.sapling(kind)
    r = get(s, 2 * n, radius)
    return result() if not r["ok"] and lib.stock.take_up_to(s, n, radius)["ok"] else r


def free(kind):
    """The empty slots the bot wants before it fells a tree of kind (lib.depot.make_room, #765, #766): stacks for twice its
    mean logs (LOGS), so a big tree of the kind fits too, OTHER, and ROOTS for a kind with roots."""
    logs = int(-(-2 * LOGS[kind] // 64))
    return logs + OTHER + (ROOTS if lib.treefarm.spec(kind).get("roots") else 0)


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


def limit(kind, name, size):
    """The most cells of the farm name of kind: size, else most(harvest(kind)) but no more than its cells that keep
    growing (ticking, #812)."""
    return size or min(most(harvest(kind)), len(ticking(kind, name)))


def built(kind, name, size):
    """How many cells of the farm name of kind count toward limit: all built, else with no size those built that keep
    growing (ticking), so cells outside them built under a size or before #812 leave its room (#815)."""
    if size:
        return lib.cells.count(name)
    return len([i for i in lib.cells.built(name) if i in ticking(kind, name)])


TICKING = {}  # a farm's name to ticking's cells, worked out once a run: a farm's layout does not change


def ticking(kind, name):
    """The indices of the farm name's cells whose saplings get random ticks while the bot is at the farm's corner, the
    low corner of cell 0, where its depot is (lib.treefarm.ticking, #812): a cell farther off grows only while the bot
    is near it."""
    if name not in TICKING:
        TICKING[name] = ticking_at(kind, name, lib.cells.cell(name, 0)[0])
    return TICKING[name]


def ticking_at(kind, name, corner):
    """ticking's cells of the farm name were its cell 0's low corner at corner instead."""
    low = lib.cells.cell(name, 0)[0]
    d = (corner.x - low.x, 0, corner.z - low.z)
    # ponytail: the first 32 cells; 8 rows of 4 of the narrowest kind (5 and a path) reach past the 2 chunks east and
    # south of the corner's that tick. Some 5 ticks of instructions a corner.
    return [i for i in range(32) if lib.treefarm.ticking(kind, corner, *[p + d for p in lib.cells.cell(name, i)])]


def hint(kind, name, at):
    """Says where a corner fits more cells that keep growing than at, the new farm name's (#813). A corner at the
    north-west block of a chunk (x and z a multiple of 16) fits the most, the same at any chunk, so it counts one such
    corner and names the nearest of those of at's chunk and of the chunks east, south and south-east. How many fit
    depends on where at lies in its chunk: from x and z 15, 9 birch cells, from 0, 20. The farm keeps at."""
    if at.x % 16 == 0 and at.z % 16 == 0:
        return  # the most already; nothing counted
    x, z = at.x // 16 * 16, at.z // 16 * 16
    best = len(ticking_at(kind, name, Pos(x, at.y, z)))
    n = len(ticking(kind, name))
    if best > n:
        c = None
        for dx in (0, 16):
            for dz in (0, 16):
                p = Pos(x + dx, at.y, z + dz)
                if c is None or p.distance(at) < c.distance(at):
                    c = p
        say(f"a corner at {c.x} {c.y} {c.z} fits {best} growing cells, this one {n}")


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


def following(kind, name, size):
    """The index of the cell build builds next: lib.cells.next, else with no size the lowest cell that keeps growing not
    built (ticking, #812). Kept as "next" in the farm's memory["cells"][name], so lib.treefarm.outside_cells keeps dirt
    gets off it (#816)."""
    i = lib.cells.next(name)
    if not size:
        i = ([j for j in ticking(kind, name) if j not in lib.cells.built(name)] + [i])[0]
    lib.cells.farm(name)["next"] = i
    return i


def build(kind, name, size, radius):
    """Builds the next cell of the farm name with the tools lib.terrain.needs for its ground and for the ground under
    its sapling spots, marks it built and says so. Gives result()."""
    i = following(kind, name, size)
    low, high = lib.cells.cell(name, i)
    tools = lib.terrain.needs(low, Pos(high.x, low.y + lib.treefarm.spec(kind)["clear"], high.z), low.y)["tools"]
    # build_cell mines the ground under each sapling spot that is not SOIL to put dirt there, such as stone (#612, #805).
    for p in lib.treefarm.spots(kind, low, high):
        if not lib.trees.is_kind(p.down(), lib.treefarm.SOIL):
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
        say(f"cells {built(kind, name, size)}/{limit(kind, name, size)}")
    return r


def gets(item, n, radius):
    """get(item, n, radius), but dirt none from under the farm's cells (lib.treefarm.get_outside, #768), and the roof's
    slabs (lib.treefarm.SLABS, a tag) are any wooden slabs the chests within radius hold (lib.stock.take_up_to takes a
    tag), and those they lack are made as oak slabs (#612). Gives result()."""
    if item == lib.treefarm.DIRT:
        return lib.treefarm.get_outside(item, n, radius)
    if item != lib.treefarm.SLABS:
        return get(item, n, radius)
    if lib.stock.take_up_to(item, n, radius)["ok"]:
        return result()
    # ponytail: lib.get takes an item, not a tag; oak is the one kind of slab it makes, a tag-aware get would make any.
    return get("minecraft:oak_slab", count("minecraft:oak_slab") + n - count(item), radius)


def first_cell(kind, name, size, radius):
    """Gets what the first cell takes (gets) and builds it. Where a get fails with no_logs, as torches take a stick and
    no tree stands within radius, grows a tree of its own (grow) and gets again, up to GROWS times (#802). Gives
    result()."""
    for item, n in lib.treefarm.materials(kind).items():
        r = growing(kind, name, lambda: gets(item, n, radius))
        if not r["ok"]:
            return r
    return build(kind, name, size, radius)


def kits(kind, name, n, radius):
    """lib.bootstrap.kit(kit(kind)); where it fails with no_logs, as the stone axe takes sticks and no tree stands within
    radius, gets the saplings (saplings, n spots) so grow has one to spare and grows a tree, as first_cell does, which it
    fells by hand (#808). Gives result()."""
    again = lambda: lib.bootstrap.kit(kit(kind), radius, gets)
    r = again()
    if not r["ok"] and r["reason"] == "no_logs":
        saplings(kind, n, radius)
        r = growing(kind, name, again)
    return r


def growing(kind, name, again):
    """again(), a get's result(); while it fails with no_logs, grows a tree (grow) and calls it again, up to GROWS times."""
    r = again()
    for _ in range(GROWS):
        if r["ok"] or r["reason"] != "no_logs" or not grow(kind, name)["ok"]:
            break
        r = again()
    return r


GROWS = 3  # trees growing grows at most, so a get that keeps failing no_logs for some other cause ends
AROUND = 8  # how far from the farm's corner grow looks for ground to plant on


def grow(kind, name):
    """Plants a spare sapling of kind (one more than a cell takes) on the GROUND (lib.trees) nearest the farm name's
    corner within AROUND, its tree's room, 2 blocks round it, off the farm's cells (lib.treefarm.outside_cells) and its
    depot's row, the air over it clear (the kind's sapling's) high; says once "waiting for a tree to grow" and waits until it
    has grown, at most 4 times GROWTH. Gives result(): ok, or no_logs where it has no sapling to spare, no ground or the
    sapling did not grow."""
    s = lib.treefarm.sapling(kind)
    if count(s) <= lib.treefarm.materials(kind)[s]:
        return result("no_logs")
    at = memory["wood_farms"][kind]
    out = lib.treefarm.outside_cells()
    clear = lib.treefarm.spec(s)["clear"]
    spot = None
    for g in scan(lib.trees.GROUND, radius=AROUND, limit=1024, around=at):
        p = g.up()
        if (not at.z - 3 <= p.z < at.z and all([out(p + (dx, 0, dz)) for dx in [-2, 2] for dz in [-2, 2]])
                and all([block(p.up(i)).is_air for i in range(clear)])):
            spot = p
            break
    # ponytail: only ground the scan finds near the corner, of the farm's kind; dirt put on stone and any sapling if needed.
    if spot is None or not lib.treefarm.plant(kind, spot)["ok"]:
        return result("no_logs")
    say("waiting for a tree to grow")
    for _ in range(int(4 * GROWTH // WAIT)):
        if block(spot).id != s:
            return result()
        wait(WAIT)
    return result("no_logs", spot)


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


def visit(kind, b):
    """Walks to cell b where the bot cannot read it (lib.treefarm.loaded), as a bot keeps only the chunks within 3 of
    its own loaded and goto loads those on its way (#811)."""
    # ponytail: a goto that fails (botsLoadChunks off) leaves the cell for another round.
    if not lib.treefarm.loaded(kind, b[0], b[1]):
        goto(lib.treefarm.torches(b[0], b[1])[0], 2)


def corner(name):
    """Walks the bot into the chunk of the farm name's corner, cell 0's low corner, where it stands elsewhere (#814), so
    the cells ticking counts get random ticks while it waits: to the floor diagonal into cell 0, kept in that chunk."""
    low = lib.cells.cell(name, 0)[0]
    p = pos()
    if p.x // 16 == low.x // 16 and p.z // 16 == low.z // 16:
        return
    # ponytail: a corner at chunk-local 15 on both axes sends it onto the corner's torch spot, which a torch does not block.
    goto(Pos(min(low.x + 1, low.x // 16 * 16 + 15), low.y + 1, min(low.z + 1, low.z // 16 * 16 + 15)))


def unplanted(kind, b):
    """The spots of cell b, a (low, high) box, that hold neither a sapling of kind nor a log; none where the bot cannot
    read the cell (lib.treefarm.loaded, #810)."""
    if not lib.treefarm.loaded(kind, b[0], b[1]):
        return []
    return [p for p in lib.treefarm.spots(kind, b[0], b[1]) if block(p).id != lib.treefarm.sapling(kind) and not lib.trees.is_log(p)]


def tend(kind, b, radius=32, told=None):
    """Puts back what cell b lacks: a sapling on each spot without one (lib.treefarm.plant: dirt under it where a path dug
    it out, getting the dirt it lacks within radius, #764, and mining what stands there first, such as a scaffold block a
    felling left; a spot it cannot plant it says once, tell with told) and its torches (a walk that pillars where a torch
    stands breaks it, and the bot picks it up), as far as the bot has them. Nothing while logs of the cell's tree stand,
    as a sapling under them never grows (#644), nor where the bot cannot read the cell (lib.treefarm.loaded, #810)."""
    if not lib.treefarm.loaded(kind, b[0], b[1]) or lib.treefarm.grown(kind, b[0], b[1]):
        return
    torches = lib.treefarm.torches(b[0], b[1])
    s = lib.treefarm.sapling(kind)
    for p in unplanted(kind, b):
        if count(s):
            r = lib.treefarm.plant(kind, p, radius)
            if not r["ok"]:
                tell({} if told is None else told, f"plant: {r['reason']} at {r['at']}")
    for t in torches:
        if block(t).id not in lib.treefarm.TORCHES and count(lib.treefarm.TORCH):
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
    return max(dict(KIT)[lib.treefarm.DIRT], lib.treefarm.spec(kind)["clear"])


def kit(kind):
    """KIT for a farm of kind: its dirt scaffold(kind)."""
    return [(item, scaffold(kind) if item == lib.treefarm.DIRT else n) for item, n in KIT]


def dirt(kind):
    """The dirt a farm of kind keeps when it stores (#762): its scaffold and a cell's materials, at least STACK. The
    rest, such as the dirt flatten and its walks dig, goes in the depot, so it never fills the bot's inventory."""
    return max(STACK, scaffold(kind) + lib.treefarm.materials(kind)[lib.treefarm.DIRT])


def reap(kind, b, radius, told, replant=True):
    """Fells the tree of cell b with an axe (lib.treefarm.harvest, which replants it but without replant). Where that
    leaves a log, says why once (tell), gets scaffold(kind) dirt to pillar with and an axe, and harvests again (#644).
    Gives the last harvest's result()."""
    ensure_tool("axe", 16, "hand", radius)
    # What earlier walks placed comes down first, out of the felling's way: harvest leaves it standing, listed (#654, #657).
    lib.trees.take_back()
    r = lib.treefarm.harvest(kind, b[0], b[1], radius, replant)
    if not r["ok"]:
        tell(told, f"harvest: {r['reason']} at {r['at']}")
        gets(lib.treefarm.DIRT, scaffold(kind), radius)
        ensure_tool("axe", 16, "hand", radius)
        lib.trees.take_back()
        r = lib.treefarm.harvest(kind, b[0], b[1], radius, replant)
    return r


def farm(kind, name, size, radius):
    """Farms forever: each round fells every grown tree (reap, so logs a failed felling left are felled again), stores
    what it carries (each round, #766), tends each cell (replants it with saplings from the chests, else from the leaves of the kind with
    lib.get), builds the next cell where the farm is below size and stocked gets what one takes (else says once what it
    lacks, and tries again BACKOFF rounds later, or in the next round that felled a tree, whose logs may make it), and
    once full composts the extra saplings (compost), and where it felled nothing picks up the drops in the farm (sweep)
    and waits in its corner's chunk (corner, #814). Each round takes back the scaffold its walks
    placed (lib.trees.take_back) before it waits, and before each felling and build (#654). Where size is None the farm grows to
    limit(kind, name, None), as its rounds learn the ticks a tree takes: from the felling to the replanting, over the trees
    felled (learn)."""
    full = False
    told = {}
    backoff = 0  # rounds until stocked is tried again
    visits = 0  # rounds that looked for a cell out of loaded range, to visit those in turn (#811)
    while True:
        lib.food.upkeep(radius)
        # What the setup steps' walks placed is not the round's; what the round's walks place (the felling's and the
        # build's own taken back by them, sweep, tend, get, reap's retry) comes down at its end (#654).
        placed_scaffold()
        own()
        # Only the cells the bot can read (#810), but each round it walks to one it cannot, in turn, so every cell is
        # farmed (#811). As the bot waits at the corner (#814), one round in turn visits none, to farm the cells there.
        cells = [lib.cells.cell(name, i) for i in lib.cells.built(name)]
        far = [b for b in cells if not lib.treefarm.loaded(kind, b[0], b[1])] + [None]
        visited = far[visits % len(far)]
        visits += 1
        if visited:
            visit(kind, visited)
        boxes = [b for b in cells if lib.treefarm.loaded(kind, b[0], b[1])]
        grown = [b for b in boxes if lib.treefarm.grown(kind, b[0], b[1])]
        keep = KEEP + [lib.treefarm.sapling(kind), (lib.treefarm.DIRT, dirt(kind))]
        full_bot = False
        # The trees of the small cells a giant farm moved from, felled once and not replanted (#807); not trees of kind,
        # so before start, not learnt.
        k, left = small(kind)
        others = 0
        for b in left:
            r = lib.depot.make_room(name, free(k), keep, radius)
            if not r["ok"]:
                full_bot = True
                tell(told, f"inventory full, store: {r['reason']}; felling stops")
                break
            if reap(k, b, radius, told, False)["ok"]:
                others += 1
        start = game_time()
        trees = 0
        for b in [] if full_bot else grown:
            # Not with too few empty slots that the depot cannot empty: the tree's drops would be dropped (#765).
            r = lib.depot.make_room(name, free(kind), keep, radius)
            if not r["ok"]:
                full_bot = True
                tell(told, f"inventory full, store: {r['reason']}; felling stops")
                break
            if reap(kind, b, radius, told)["ok"]:
                trees += 1
        felled = trees + others > 0
        # Every round, not only one that fells: what builds up between fellings, such as the dirt flatten and walks dig,
        # goes in the depot too (#766). A store with nothing over keep walks nowhere.
        if not full_bot:
            r = lib.depot.store(name, keep, radius)
            # Once a reason, not every round: a full depot that takes no new chest leaves the logs carried, more each
            # round (#763).
            if not r["ok"] and "store: " + r["reason"] not in told:
                told["store: " + r["reason"]] = True
                say(f"store: {r['reason']}, kept {r['missing']}")
        # After the felling, so lib.get breaks the felled trees' leaves for saplings, not those of a tree still to fell.
        n = sum([len(unplanted(kind, b)) for b in boxes])
        s = lib.treefarm.sapling(kind)
        if n and count(s) < n and not lib.stock.take_up_to(s, n, radius)["ok"]:
            get(s, n, radius)
        if visited:
            visit(kind, visited)  # again where the store walked away from it
        for b in boxes:
            tend(kind, b, radius, told)
        stored_bone_meal(kind, name, boxes, radius)
        # Once the bot has the saplings for a giant, the next cells are giants: a farm of its own beside this one, whose
        # run never returns but on a failed setup step (#803).
        # Its trees the giant farm's rounds fell, its cells not replanted (small, #807).
        # ponytail: a chest scan a round (stocked's gets scan too).
        if kind in GIANTS and fours(kind, radius):
            return run(GIANTS[kind], beside(name), None, radius)
        if felled:
            learn(kind, game_time() - start, trees)
            backoff = 0
        most_cells = limit(kind, name, size)
        have = built(kind, name, size)
        if full and have < most_cells:
            say(f"cells {have}/{most_cells}")  # it learnt it may grow (#662); "farm full" again once it is
            full = False
        if have < most_cells and backoff:
            backoff -= 1
        elif have < most_cells:
            # Before stocked's dirt get (#816); a farm not laid out, as a test's with lib.cells stubbed, has no cell to keep.
            if name in memory.get("cells", {}):
                following(kind, name, size)
            r = stocked(kind, radius)
            if r["ok"]:
                build(kind, name, size, radius)
            else:
                tell(told, f"next cell: {r['reason']}, lacks {r['missing']}")
                backoff = BACKOFF
        elif not full:
            say("farm full")
            full = True
        if full:
            r = compost(kind, name, boxes, radius)
            if not r["ok"]:
                tell(told, f"compost: {r['reason']}")
        if not felled:
            sweep(kind, boxes)
            corner(name)
        lib.trees.take_back()
        if not felled:
            wait(WAIT)


EXTRA = 32  # the saplings beyond what replanting keeps (materials) over which a full farm composts them (#804)
COMPOSTER = "minecraft:composter"
BONE_MEAL = "minecraft:bone_meal"


def compost(kind, name, boxes, radius):
    """Where the bot and the saplings chests of the depot name hold more than EXTRA saplings of kind beyond materials(kind)
    keeps (#804): makes a composter 2 west of the depot's first chest (lib.get) unless one stands there, composts all
    but those it keeps in it (use), takes out the bone meal and uses it on the saplings of boxes (fertilize); what is
    left the next store puts in the depot's bone_meal chest. Gives result(): ok, or the reason the composter could not
    be made or used; ok with nothing done where no depot name was laid out (a farm of swapped functions)."""
    if name not in memory.get("depots", {}):
        return result()
    s = lib.treefarm.sapling(kind)
    keep = materials(kind)[s]
    depot = lib.depot.chests(name, "saplings")
    held = chest_held(s, radius, depot)[1]
    if count(s) + held - keep <= EXTRA:
        return result()
    c = lib.depot.depot(name)["origin"] + (-2, 0, 0)
    if block(c).id != COMPOSTER:
        r = get(COMPOSTER, 1, radius)
        if not r["ok"]:
            return r
        r = place(c, COMPOSTER)
        if not r:
            return result(r.reason, c)
    lib.stock.take_up_to(s, count(s) + held, radius, depot)  # ponytail: what fits on the bot; the rest next round
    while True:
        level = block(c).state["level"]
        if level == 7:
            wait(5)  # the composter turns to 8, bone meal, 20 ticks after it reaches 7
            continue
        if level < 8 and count(s) <= keep:
            break
        r = use(c) if level == 8 else use(c, s)  # at 8 the use takes the bone meal out, whatever the bot holds
        if not r:
            return result(r.reason, c)
    pick_up(BONE_MEAL)
    fertilize(kind, boxes)
    return result()


def stored_bone_meal(kind, name, boxes, radius):
    """Where a cell of boxes has a sapling of kind, takes the bone meal of the depot name's bone_meal chests (a stack at
    most) and uses it on them (fertilize, #809); the next store puts back what is left."""
    if name not in memory.get("depots", {}):
        return
    s = lib.treefarm.sapling(kind)
    if not [p for b in boxes for p in lib.treefarm.spots(kind, b[0], b[1]) if lib.trees.is_kind(p, s)]:
        return
    lib.stock.take_up_to(BONE_MEAL, STACK, radius, lib.depot.chests(name, "bone_meal"))
    fertilize(kind, boxes)


def fertilize(kind, boxes):
    """Uses the bot's bone meal on each sapling of kind in the cells boxes until it has grown (#804)."""
    s = lib.treefarm.sapling(kind)
    for b in boxes:
        for p in lib.treefarm.spots(kind, b[0], b[1]):
            while count(BONE_MEAL) and lib.trees.is_kind(p, s) and use(p, BONE_MEAL):
                pass


def run(kind, at=None, size=None, radius=32):
    """Grows and farms a wood farm of kind (a kind of lib.treefarm.KINDS) with its first corner at at (where the bot
    stands, or where the last farm of kind started), up to size cells (limit: most of the ticks a tree takes, learnt,
    else HARVEST[kind]). Never returns but on a
    failed setup step: gives lib.project.run's result() then."""
    kind = giant(lib.treefarm.key(kind), radius)
    name = kind.split(":")[-1].replace("_sapling", "") + "_farm"
    farms = memory.get("wood_farms", {})
    new = kind not in farms
    at = Pos(at) if at is not None else farms.get(kind, pos())
    farms[kind] = at
    memory["wood_farms"] = farms
    w = lib.treefarm.spec(kind)["size"]
    lib.cells.layout(name, at.down(), (w, w))
    if new:
        hint(kind, name, at)
    # The depot north of the farm, a path's width off its first row, going east along it.
    lib.depot.depot(name, at + (0, 0, -2), "east", CATEGORIES)
    # Home is the path by the depot, so a bot that dies comes back at the farm, and recover fetches what it dropped: a
    # death costs it no more than lib.cost.death (#679).
    set_home(at + (0, 0, -1))
    lib.trees.recover()
    n = 4 if lib.treefarm.spec(kind)["square"] else 1
    steps = [Step("kit", kits, (kind, name, n, radius)),
             Step("sapling", saplings, (kind, n, radius)),
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
