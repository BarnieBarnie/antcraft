# Farming, for any script: `import lib.farm`, then lib.farm.tend(low, high) over a field of soil. colony.farmer is built
# on it, and so is jobs.farm (docs/reference/modules/lib/farm.md).
# A module of the mod's default library (lib/), a skill: it imports only lib.result. It assumes a hoe to till with and the
# seeds to plant; lib.get gets those.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.result import result

# (crop block, seed item, harvest item, ripe age); wheat first, so it is planted
# where the bot carries several kinds of seed.
CROPS = [
    ("minecraft:wheat", "minecraft:wheat_seeds", "minecraft:wheat", 7),
    ("minecraft:carrots", "minecraft:carrot", "minecraft:carrot", 7),
    ("minecraft:potatoes", "minecraft:potato", "minecraft:potato", 7),
    ("minecraft:beetroots", "minecraft:beetroot_seeds", "minecraft:beetroot", 3),
]
POISON = "minecraft:poisonous_potato"
SOIL = ["minecraft:dirt", "minecraft:grass_block"]
FARMLAND = "minecraft:farmland"


def crop(name):
    """The CROPS entry whose crop, seed or harvest is name (with or without "minecraft:"), or None."""
    name = name if ":" in name else "minecraft:" + name
    for c in CROPS:
        if name in c[:3]:
            return c
    return None


def ripe(p):
    """The crop block id at p where a crop of CROPS grows there ripe, else None."""
    b = block(p)
    for c in CROPS:
        if b is not None and b.id == c[0] and b.state["age"] >= c[3]:
            return c[0]
    return None


def field(low, high):
    """The spots of the box from low to high (two corners) to farm, row by row: in each column, its top block that is
    neither air nor a crop where that is farmland, dirt or grass. Reads each column from the top of the box down to that
    block, so a box of soil one block high reads two blocks a column."""
    crops = [c[0] for c in CROPS]
    spots = []
    for z in range(min(low.z, high.z), max(low.z, high.z) + 1):
        for x in range(min(low.x, high.x), max(low.x, high.x) + 1):
            p = Pos(x, max(low.y, high.y), z, low.dimension)
            up = block(p.up())
            if up is None or not (up.is_air or up.id in crops):
                continue
            b = block(p)
            while b is not None and (b.is_air or b.id in crops) and p.y > min(low.y, high.y):
                p = p.down()
                b = block(p)
            if b is not None and (b.id == FARMLAND or b.id in SOIL):
                spots.append(p)
    return spots


def till(spots):
    """Tills each of spots that is dirt or grass with air over it, with a hoe the bot carries. Gives how many it tilled."""
    n = 0
    for soil in spots:
        if block(soil).id in SOIL and block(soil.up()).is_air and use(soil, "#minecraft:hoes"):
            n += 1
    return n


def plant(spots, seed=None):
    """Plants seed (None: the first seed of CROPS the bot carries, wheat first) on each of spots that is farmland with
    air over it, while the bot carries it. Gives how many it planted."""
    n = 0
    for soil in spots:
        if block(soil).id == FARMLAND and block(soil.up()).is_air:
            for s in ([seed] if seed is not None else [c[1] for c in CROPS]):
                if has(s):
                    place(soil.up(), s)
                    n += 1
                    break
    return n


def harvest(spots):
    """Harvests the ripe crop over each of spots (mine puts the drops in the bot's inventory) and replants it with its own
    seed where the bot carries one. Unripe crops are left. Gives how many it harvested."""
    n = 0
    for soil in spots:
        c = crop(ripe(soil.up()) or "none")
        if c is not None:
            mine(soil.up())
            n += 1
            plant([soil], c[1])
    return n


def tend(low, high, seed=None):
    """Harvests, tills and plants the field from low to high (field) with seed (None: what the bot carries, wheat first).
    Gives result(): ok; blocked at the first spot left untilled, or left bare though the bot still carries its seed, which
    is worth another try (lib.retry); or no_item with missing, the seeds still lacking for the farmland left bare."""
    spots = field(low, high)
    harvest(spots)
    till(spots)
    plant(spots, seed)
    seeds = [seed] if seed is not None else [c[1] for c in CROPS]
    bare = [s for s in spots if block(s).id == FARMLAND and block(s.up()).is_air]
    stuck = [s for s in spots if block(s).id in SOIL] + (bare if any([has(s) for s in seeds]) else [])
    if stuck:
        return result("blocked", stuck[0])
    if bare:
        return result("no_item", None, {seed or CROPS[0][1]: len(bare)})
    return result()
