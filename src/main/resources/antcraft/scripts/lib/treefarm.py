# Tree cells for a wood farm, one tree each, shaped by the sapling kind, for any script: `import lib.treefarm`, then
# lib.treefarm.build_cell("minecraft:birch_sapling", low, high), lib.treefarm.grown(...) and lib.treefarm.harvest(...)
# on a cell of lib.cells (docs/reference/modules/lib/treefarm.md).
# A module of the mod's default library (lib/), a kit over lib.terrain, lib.area and lib.trees. It assumes what
# materials(kind) lists and the tools to clear the cell with; lib.get gets those.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.area
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
}
TORCH = "minecraft:torch"
DIRT = "minecraft:dirt"
SLABS = "#minecraft:wooden_slabs"
# What harvest picks up besides the logs and saplings fell_whole does: what leaves drop.
DROPS = ["minecraft:stick", "minecraft:apple"]


def spec(kind):
    """KINDS[kind], kind with or without minecraft:."""
    return KINDS[kind if ":" in kind else "minecraft:" + kind]


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
    lights every floor block of a cell up to 8 wide."""
    low = Pos(low)
    high = Pos(high)
    return [Pos(low.x, low.y + 1, low.z), Pos(high.x, low.y + 1, high.z)]


def materials(kind):
    """The items one cell of kind takes, item to count: dirt under each sapling, the saplings, torches and roof slabs.
    The fill to level the cell is lib.terrain.needs's."""
    n = 4 if spec(kind)["square"] else 1
    out = {DIRT: n, kind: n, TORCH: 2}
    slabs = len(roof(kind, Pos(0, 0, 0), Pos(spec(kind)["size"] - 1, 0, spec(kind)["size"] - 1)))
    if slabs:
        out[SLABS] = slabs
    return out


def build_cell(kind, low, high):
    """Builds the cell of kind with corners low and high (low.y the floor): flattens the floor at low.y and clears the
    clear blocks over it (lib.terrain.flatten), puts dirt under each sapling spot, the torches and the roof, then plants.
    Gives result(): ok, or the reason of the step that failed with at."""
    low = Pos(low)
    high = Pos(high)
    s = spec(kind)
    ps = spots(kind, low, high)
    r = lib.terrain.flatten(low, Pos(high.x, low.y + s["clear"], high.z), low.y)
    if not r["ok"]:
        return r
    for p in ps:
        ground = p.down()
        if not lib.trees.is_kind(ground, "#minecraft:dirt"):
            r = mine(ground)
            if not r and r.reason != "target_gone":
                return result(r.reason, ground)
            r = place(ground, DIRT)
            if not r:
                return result(r.reason, ground)
    for t in torches(low, high):
        if not lib.trees.is_kind(t, TORCH):
            r = place(t, TORCH)
            if not r:
                return result(r.reason, t)
    # The walks up to the roof pillar; that scaffold comes down before the saplings go in.
    placed_scaffold()
    for q in roof(kind, low, high):
        if block(q).is_air:
            r = place(q, SLABS, half="bottom")
            if not r:
                lib.trees.take_back()
                return result(r.reason, q)
    lib.trees.take_back()
    for p in ps:
        if block(p).is_air:
            r = place(p, kind)
            if not r:
                return result(r.reason, p)
    return result()


def grown(kind, low, high):
    """Whether the cell's tree has grown: a log at its sapling spot."""
    return lib.trees.is_log(spots(kind, low, high)[0])


def harvest(kind, low, high):
    """Fells the cell's tree whole and replants (lib.trees.fell_whole), then picks up the sticks and apples lying about.
    Gives fell_whole's result()."""
    r = lib.trees.fell_whole(spots(kind, low, high)[0], True)
    # A torch a pillar up to the leaves took the place of lies about too; the pillars to drops on the leaves come down.
    pick_up(DROPS + [TORCH])
    lib.trees.take_back()
    return r
