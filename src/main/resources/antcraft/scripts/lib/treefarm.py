# Tree cells for a wood farm, one tree each, shaped by the sapling kind, for any script: `import lib.treefarm`, then
# lib.treefarm.build_cell("minecraft:birch_sapling", low, high), lib.treefarm.grown(...) and lib.treefarm.harvest(...)
# on a cell of lib.cells (docs/reference/modules/lib/treefarm.md).
# A module of the mod's default library (lib/), a kit over lib.terrain, lib.area and lib.trees. It assumes what
# materials(kind) lists and the tools to clear the cell with; lib.get gets those, and harvest gets the saplings to
# replant with that it lacks.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.area
import lib.get
import lib.terrain
import lib.trees
from lib.result import result

# Per sapling, from the 26.3 tree features (TreeFeatures) its TreeGrower grows:
# - size: the cell's width and depth, so a tree's logs stay in its cell and its canopy does not reach the next tree's;
# - square: a 2x2 trunk from four saplings;
# - clear: the blocks of air the sapling needs over the floor. TreeFeature.getMaxFreeTreeHeight gives y - 2 for the
#   first level y over the sapling (0 its own) that is not free, and the tree grows only where that is its trunk height
#   or more (a fancy oak clips down to 4): so clear is the tallest trunk plus 2;
# - roof: a ring of bottom slabs (no mob spawns on a bottom slab) right over the clear blocks, round the trunk column.
# The space check looks within FeatureSize.getSizeAtHeight of the trunk, 0 at the sapling's own level for all of these,
# so torches on the floor at the cell's corners do not stop a tree.
KINDS = {
    # OAK: StraightTrunkPlacer(4, 2, 0), trunk 4 to 6, BlobFoliagePlacer radius 2, TwoLayersFeatureSize(1, 0, 1). One in
    # ten is FANCY_OAK: FancyTrunkPlacer(3, 11, 0), trunk 3 to 14, branches out some 3 blocks, TwoLayersFeatureSize with
    # a min clipped height of 4. Roofed at 8, the oak always grows and a fancy oak clips to a trunk of 6.
    "minecraft:oak_sapling": {"size": 7, "square": False, "clear": 8, "roof": True},
    # BIRCH (or BIRCH_BEES_005; a sapling never grows the super birch): StraightTrunkPlacer(5, 2, 0), trunk 5 to 7,
    # BlobFoliagePlacer radius 2.
    "minecraft:birch_sapling": {"size": 5, "square": False, "clear": 9, "roof": False},
    # SPRUCE: StraightTrunkPlacer(5, 2, 1), trunk 5 to 8, SpruceFoliagePlacer radius 2 to 3. One sapling a cell, so never
    # MEGA_SPRUCE or MEGA_PINE (GiantTrunkPlacer(13, 2, 14), four saplings).
    "minecraft:spruce_sapling": {"size": 7, "square": False, "clear": 10, "roof": False},
    # JUNGLE_TREE_NO_VINE: StraightTrunkPlacer(4, 8, 0), trunk 4 to 12, BlobFoliagePlacer radius 2. One sapling a cell,
    # so never MEGA_JUNGLE_TREE.
    "minecraft:jungle_sapling": {"size": 5, "square": False, "clear": 14, "roof": False},
    # DARK_OAK, its grower's only tree, from four saplings: DarkOakTrunkPlacer(6, 2, 1), trunk 6 to 9, leaning up to 2 and
    # branching 1 out of the 2x2, so its logs stay 3 blocks of it; ThreeLayersFeatureSize(1, 1, 0, 1, 2).
    "minecraft:dark_oak_sapling": {"size": 8, "square": True, "clear": 11, "roof": False},
    # PALE_OAK_BONEMEAL, its grower's only tree, from four saplings: DARK_OAK's DarkOakTrunkPlacer(6, 2, 1) and
    # ThreeLayersFeatureSize(1, 1, 0, 1, 2), so the same cell.
    "minecraft:pale_oak_sapling": {"size": 8, "square": True, "clear": 11, "roof": False},
    # ACACIA: ForkingTrunkPlacer(5, 2, 2), trunk 5 to 9, leaning up to 3 one way and forking a branch up to 3 another,
    # so its logs stay 3 blocks of it; AcaciaFoliagePlacer radius 2, TwoLayersFeatureSize(1, 0, 2).
    "minecraft:acacia_sapling": {"size": 7, "square": False, "clear": 11, "roof": False},
    # CHERRY (or CHERRY_BEES_005): CherryTrunkPlacer(7, 1, 0, ...), trunk 7 to 8, one to three branches of 2 to 4 out
    # and one more for a branch that does not end above its start, so its logs stay 5 blocks of it; CherryFoliagePlacer
    # radius 4, TwoLayersFeatureSize(1, 0, 2).
    "minecraft:cherry_sapling": {"size": 11, "square": False, "clear": 10, "roof": False},
    # The giant kinds grow from four of the sapling a one-sapling kind grows from, so their key is a name of their own and
    # sapling names the item (#650).
    # MEGA_SPRUCE or, one in two, MEGA_PINE: GiantTrunkPlacer(13, 2, 14), a 2x2 trunk 13 to 29, no branches;
    # MegaPineFoliagePlacer's canopy reaches 4 out of the trunk; TwoLayersFeatureSize(1, 1, 2).
    "giant_spruce": {"sapling": "minecraft:spruce_sapling", "size": 10, "square": True, "clear": 31, "roof": False},
    # MEGA_JUNGLE_TREE: MegaJungleTrunkPlacer(10, 2, 19), a 2x2 trunk 10 to 31, branches of 5 logs reaching up to 4 out of
    # it; TwoLayersFeatureSize(1, 1, 2).
    "giant_jungle": {"sapling": "minecraft:jungle_sapling", "size": 10, "square": True, "clear": 33, "roof": False},
    # RED_POPLAR, ORANGE_POPLAR or YELLOW_POPLAR (new in 26.3): PoplarTrunkPlacer(7, 4, 0), trunk 7 to 11, 1 to 4
    # branches of one log 5 below its top; PoplarFoliagePlacer puts more logs in its canopy, whose leaves reach 7 out.
    # Measured over 200 trees (#650): logs up to 3 out of the trunk and 11 over the sapling, 15 a tree on average;
    # TwoLayersFeatureSize(1, 0, 2).
    "minecraft:poplar_sapling": {"size": 7, "square": False, "clear": 13, "roof": False},
    # TALL_MANGROVE, or 15 in 100 MANGROVE (#650): MangroveRootPlacer lifts the trunk 3 to 7 (MANGROVE 1 to 3) over the
    # propagule's spot on roots that spread out and down to the ground; UpwardsBranchingTrunkPlacer(4, 1, 9), a trunk of 4
    # to 14 (MANGROVE's (2, 1, 4)), its branches stepping out and up 1 to 6; TwoLayersFeatureSize(3, 0, 2). So clear is
    # the highest trunk top plus 2. Measured over 188 grown trees: logs up to 5 out of the trunk, roots up to 7 out and 7
    # high (so up to 2 into the next cell, whose harvest mines them), 16 logs a tree. roots: harvest mines the roots in
    # the cell, as one may stand at the spot and they pile up.
    "minecraft:mangrove_propagule": {"size": 11, "square": False, "clear": 23, "roof": False, "roots": True},
}
TORCH = "minecraft:torch"
# A torch at a torch spot, standing or on a wall: a wall torch is a block of its own (#760).
TORCHES = [TORCH, "minecraft:wall_torch"]
DIRT = "minecraft:dirt"
SLABS = "#minecraft:wooden_slabs"
# What harvest picks up besides the logs and saplings fell_whole does: what leaves drop.
DROPS = ["minecraft:stick", "minecraft:apple"]
ROOTS = ["minecraft:mangrove_roots", "minecraft:muddy_mangrove_roots"]


def key(kind):
    """kind as KINDS has it: a sapling with or without minecraft:, or a giant kind's own name."""
    return kind if kind in KINDS or ":" in kind else "minecraft:" + kind


def spec(kind):
    """KINDS[kind], kind as key takes it."""
    return KINDS[key(kind)]


def sapling(kind):
    """The item that grows kind: the kind itself, or the sapling a giant kind names."""
    return spec(kind).get("sapling", key(kind))


def spots(kind, low, high):
    """Where the cell's sapling (four for a square kind) goes: over the floor in the cell's middle."""
    c = lib.area.centre(low, high).up()
    if spec(kind)["square"]:
        return [c, c.east(), c.south(), c.east().south()]
    return [c]


def roof(kind, low, high):
    """Where the cell's roof slabs go: a ring round the trunk and over it, clear blocks over the floor; [] for no roof."""
    s = spec(kind)
    if not s["roof"]:
        return []
    ps = spots(kind, low, high)
    a, b = lib.area.bounds(ps, 1)
    return lib.area.box(Pos(a.x, ps[0].y + s["clear"], a.z), Pos(b.x, ps[0].y + s["clear"], b.z))


def torches(low, high):
    """Where the cell's torches go: on the floor at two opposite corners. Torch light 14 falls by one a block, so it
    lights every floor block of a cell up to 14 wide with no tree in it, and of the widest kind's, the cherry's 11, with
    its tree grown."""
    low = Pos(low)
    high = Pos(high)
    return [Pos(low.x, low.y + 1, low.z), Pos(high.x, low.y + 1, high.z)]


def materials(kind):
    """The items one cell of kind takes, item to count: dirt under each sapling, the saplings, torches, roof slabs and,
    for a roof, clear more dirt to pillar up to it with, which build_cell takes back. The fill to level the cell depends
    on its ground: lib.terrain.needs gives it."""
    n = 4 if spec(kind)["square"] else 1
    out = {DIRT: n, sapling(kind): n, TORCH: 2}
    slabs = len(roof(kind, Pos(0, 0, 0), Pos(spec(kind)["size"] - 1, 0, spec(kind)["size"] - 1)))
    if slabs:
        out[SLABS] = slabs
        # ponytail: a pillar the roof's height, the most a walk up to it places; it comes back.
        out[DIRT] += spec(kind)["clear"]
    return out


def build_cell(kind, low, high):
    """Builds the cell of kind with corners low and high (low.y the floor): flattens the floor at low.y and clears the
    clear blocks over it (lib.terrain.flatten, keeping a torch at a torch spot), puts dirt under each sapling spot (soil), the torches and the roof, then plants.
    Gives result(): ok, or the reason of the step that failed with at."""
    low = Pos(low)
    high = Pos(high)
    s = spec(kind)
    ps = spots(kind, low, high)
    # The cell's own torches stay, as when a build that failed is tried again (#760).
    kept = [t for t in torches(low, high) if block(t).id in TORCHES]
    r = lib.terrain.flatten(low, Pos(high.x, low.y + s["clear"], high.z), low.y, keep=kept)
    if not r["ok"]:
        return r
    for p in ps:
        r = soil(p.down())
        if not r["ok"]:
            return r
    for t in torches(low, high):
        if block(t).id not in TORCHES:
            r = place(t, TORCH)
            if not r:
                return result(r.reason, t)
    # The walks up to the roof pillar; that scaffold comes down before the saplings go in. What earlier walks placed
    # stays, listed for the caller (#657).
    earlier = placed_scaffold(False)
    for q in roof(kind, low, high):
        if block(q).is_air:
            r = place(q, SLABS, half="bottom")
            if not r:
                lib.trees.take_back(earlier)
                return result(r.reason, q)
    lib.trees.take_back(earlier)
    for p in ps:
        if block(p).is_air:
            r = plant(kind, p)
            if not r["ok"]:
                return r
    return result()


def soil(ground, radius=32):
    """Puts dirt at ground, a sapling spot's floor, where there is none: mines what is there, such as stone or a block a
    path put in, getting the dirt it lacks within radius (lib.get). Gives result(): ok, or the reason with at."""
    if lib.trees.is_kind(ground, "#minecraft:dirt"):
        return result()
    if not block(ground).is_air:
        r = mine(ground)
        if not r and r.reason != "target_gone":
            return result(r.reason, ground)
    if not count(DIRT):
        # ponytail: lib.get may dig the dirt it gets from under another spot; the farm keeps a stack (#762), so seldom.
        r = lib.get.get(DIRT, 1, radius)
        if not r["ok"]:
            return r
    r = place(ground, DIRT)
    return result() if r else result(r.reason, ground)


def plant(kind, p, radius=32):
    """Plants a sapling of kind at the spot p, the bot carrying one: dirt under it first where a path dug it out (soil,
    #764), then mines what stands at p. Gives result(): ok, or the reason with at."""
    r = soil(p.down(), radius)
    if not r["ok"]:
        return r
    if not block(p).is_air:
        mine(p)
    r = place(p, sapling(kind))
    return result() if r else result(r.reason, p)


def within(what, kind, low, high):
    """The blocks what selects in the cell's tree space, the cell up to clear blocks over the floor."""
    low = Pos(low)
    high = Pos(high)
    a = Pos(low.x, low.y + 1, low.z)
    b = Pos(high.x, low.y + spec(kind)["clear"], high.z)
    c = lib.area.centre(a, b)
    # One scan of the sphere round the tree space, its corners in, some 20,000 blocks read for a giant's (a block()
    # read of each would cost 30 instructions, the scan 1 per 8 blocks).
    r = int(max([(p.x - c.x) ** 2 + (p.y - c.y) ** 2 + (p.z - c.z) ** 2 for p in [a, b]]) ** 0.5) + 1
    return [p for p in scan(what, radius=min(r, 32), limit=1024, around=c) if lib.area.inside(p, a, b)]


def standing(kind, low, high):
    """The lowest log of the cell's tree still standing, in its tree space (within): the lowest up a column over a
    sapling spot, so also one a failed harvest left over the sapling (#644) or a mangrove's trunk on its roots (#650),
    else the lowest anywhere in it, such as a branch an acacia or cherry harvest left off the trunk (#651); None where
    there is none."""
    logs = within(lib.trees.LOGS, kind, low, high)
    if not logs:
        return None
    columns = [(p.x, p.z) for p in spots(kind, low, high)]
    trunk = [p for p in logs if (p.x, p.z) in columns]
    return min(trunk or logs, key=lambda p: p.y)


def grown(kind, low, high):
    """Whether the cell has a tree to fell: a log in its tree space (standing), at its sapling spot or one a failed
    harvest left, over it, which stops the sapling growing, or off it."""
    return standing(kind, low, high) is not None


def harvest(kind, low, high, radius=32):
    """Fells the cell's tree whole from its lowest log standing (lib.trees.fell_whole), again while a log is left, such
    as one cut off from the tree, mines the roots in the cell where the kind has them (a mangrove's), picks up the
    sticks and apples lying about, then, where it felled the tree whole, plants each empty sapling spot (plant, so
    with dirt put back under it), getting the saplings it lacks within radius (lib.get), such as a square kind with fewer than four carried; where it
    cannot, tend does later.
    Gives fell_whole's result(): ok, also where nothing stands; out_of_reach or no_space with at, the log left, which
    grown still sees."""
    # Again while a log is left standing: a bot that died up a giant jungle and had mined the logs round a branch log left
    # it cut off from the tree, which fell_whole no longer reaches (#713). ponytail: at most 4 fellings, a backstop.
    r = result()
    for _ in range(4):
        base = standing(kind, low, high)
        if base is None:
            break
        r = lib.trees.fell_whole(base, False)
        if not r["ok"]:
            break
    # The highest first, so none is left out of reach over a pillar's top; one mine could not reach is tried again in a
    # second pass, from where the bot stands then. ponytail: two passes. mine puts the roots into the inventory, so
    # none lie about (#751), and jobs.wood_farm stores them in its depot.
    if r["ok"] and spec(kind).get("roots"):
        for _ in range(2):
            for p in sorted(within(ROOTS, kind, low, high), key=lambda p: -p.y):
                mine(p)
    # A torch a pillar up to the leaves took the place of lies about too. Only what lies on the floor or a step over it:
    # pillaring or digging up to each drop on the decaying leaves took most of a dark oak's harvest, and jobs.wood_farm's
    # sweep picks those up later (#655).
    for e in items_near(DROPS + [TORCH], 8):
        if e.valid and e.pos.y <= Pos(low).y + 2 and goto(e.pos, keep="#antcraft:scaffold_blocks"):
            pick_up(DROPS + [TORCH], 1)
    lib.trees.take_back()
    # Planted last: a pillar up to the leaves fell_whole climbed took a sapling's place (#652).
    if r["ok"]:
        empty = [p for p in spots(kind, low, high) if block(p).is_air]
        if empty and lib.get.get(sapling(kind), len(empty), radius)["ok"]:
            for p in empty:
                plant(kind, p, radius)
    return r
