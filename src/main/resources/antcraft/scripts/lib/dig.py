# Getting things out of the ground, for any script: `import lib.dig`, then lib.dig.stone(8), lib.dig.ore("raw_iron", 3),
# lib.dig.stairs_to(16), lib.dig.branch(32), lib.dig.stairs_up().
# A module of the mod's default library (lib/), a skill: it assumes the bot holds a pickaxe that drops what it digs
# (lib.get makes sure of that).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.find
import lib.mine
import lib.shaft
from lib.result import result

COBBLESTONE = "minecraft:cobblestone"
# What mining gives cobblestone from.
STONE = ["minecraft:stone", "minecraft:cobblestone"]
# The ore blocks each raw item comes from.
ORES = {
    "coal": "#c:ores/coal",
    "raw_iron": "#c:ores/iron",
    "raw_copper": "#c:ores/copper",
    "raw_gold": "#c:ores/gold",
    "redstone": "#c:ores/redstone",
    "lapis_lazuli": "#c:ores/lapis",
    "diamond": "#c:ores/diamond",
    "emerald": "#c:ores/emerald",
}
# The height where each is most common.
BEST_Y = {"coal": 96, "raw_iron": 16, "raw_copper": 48, "raw_gold": -16, "redstone": -59, "diamond": -59, "lapis_lazuli": 0}
FACING = {"north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0)}
# How many branches ore digs at its depth, and how long, before it gives up.
ROUNDS = 4
LENGTH = 32


def short(item):
    """item without the minecraft: namespace."""
    return item[10:] if item.startswith("minecraft:") else item


def best_y(item):
    """The height where the ore of item, a raw item such as "raw_iron", is most common; None for one that is no ore."""
    return BEST_Y.get(short(item))


def stone(n, radius=32):
    """Mines stone within radius, exposed stone first, until the bot carries n more cobblestone, then walks back to
    where it started without building with the cobblestone, out of a pit it may have dug. Gives result(): ok, no_space,
    or no_stone with missing once no stone it can mine is left."""
    target = count(COBBLESTONE) + n
    start = pos()
    skip = {}
    r = result()
    while count(COBBLESTONE) < target:
        rock = lib.find.exposed(STONE, radius, skip) or lib.find.nearest(STONE, radius, lambda p: p not in skip)
        if rock is None:
            r = result("no_stone", None, {COBBLESTONE: target - count(COBBLESTONE)})
            break
        m = mine(rock)
        if not m:
            if m.reason == "no_space":
                r = result("no_space", rock)
                break
            skip[rock] = True  # out of reach or stuck: try another
    goto(start, keep=COBBLESTONE)
    return r


def ore(item, n, radius=32, y=None, facing="north", length=LENGTH):
    """Makes the bot carry n more of item, a raw item such as "raw_iron" or "coal": mines the exposed veins of its ore
    within radius, nearest first; where they give too few, digs stairs down to y (best_y(item) where None) towards
    facing, branches of length on from there and climbs back up. Gives result(): ok, no_recipe for an item that is no
    ore, no_ore with missing where ROUNDS branches found too little or there is no y to dig to (emerald), or where a step
    failed."""
    key = short(item)
    if key not in ORES:
        return result("no_recipe", None, {"minecraft:" + key: n})
    target = count(key) + n
    skip = {}
    while count(key) < target:
        p = lib.find.exposed(ORES[key], radius, skip)
        if p is None:
            break
        skip[p] = True  # once tried, whether its vein came out or not
        for b in ore_vein(p):
            r = mine(b)
            if not r and r.reason == "no_space":
                return result("no_space", b)
    if count(key) >= target:
        return result()
    y = best_y(key) if y is None else y
    if y is None:
        return result("no_ore", None, {"minecraft:" + key: target - count(key)})
    r = stairs_to(y, facing)
    for _ in range(ROUNDS):
        if not r["ok"] or count(key) >= target:
            break
        r = branch(length, facing=facing)
    up = stairs_up()
    if not r["ok"]:
        return r
    if count(key) < target:
        return result("no_ore", None, {"minecraft:" + key: target - count(key)})
    return up


def cut(p, stand, opened):
    """Digs p standing at stand, as lib.shaft.dig does: a fluid or falling block beside it is walled off, air beside it
    is left open. None once done, else a result."""
    b = block(p)
    if b is not None and b.is_air:
        return None
    air = [s for s in (p.north(), p.south(), p.east(), p.west(), p.up()) if lib.find.is_air(s)]
    return lib.shaft.dig(p, opened + air, stand)


def stairs_to(y, facing="north"):
    """Digs a 1-wide staircase from the bot's feet down to y, one block towards facing for each block down, and walks
    down it. Each step is three blocks tall, so the bot walks back up it; it never digs straight down. Remembers the top
    in memory["stairs"] for stairs_up, also where it goes on from the bottom of a staircase it dug before. Gives
    result(): ok, or the reason a step failed with at."""
    step = FACING[facing]
    here = pos()
    kept = memory.get("stairs")
    top = kept["top"] if kept is not None and kept["bottom"] == here else here
    opened = [here, here.up(), here.up(2)]
    while here.y > y:
        down = here + (step[0], -1, step[2])
        cells = [down.up(2), down.up(), down]
        opened = opened + cells
        for p in cells:
            failure = cut(p, here, opened)
            if failure:
                return failure
        r = goto(down)
        if not r:
            return result(r.reason, down)
        here = down
        memory["stairs"] = {"top": top, "bottom": here}
    return result()


def stairs_up(facing="north"):
    """Walks back up the staircase stairs_to dug, to its top; where it remembers none, digs one up towards facing to the
    surface. Gives result(): ok, no_surface in the Nether, or goto's reason or the reason a step failed with at."""
    kept = memory.get("stairs")
    if kept is None:
        return dig_up(facing)
    r = goto(kept["top"])
    if not r:
        return result(r.reason, kept["top"])
    memory.pop("stairs")
    return result()


def surfaced(here, step):
    """Whether the bot at here walks out towards step without digging: the block ahead of its feet is air and nothing
    that blocks motion is above it but leaves (surface)."""
    top = surface(here)
    return lib.find.is_air(here + step) and (top is None or here.y >= top.y)


def is_floor(p):
    """Whether the block at p is one to stand on: loaded, neither air nor a fluid."""
    b = block(p)
    return b is not None and not b.is_air and not b.is_fluid


def dig_up(facing):
    """Digs a 1-wide staircase from the bot's feet up towards facing, one block on for each block up, and walks up it
    until surfaced. Where the block ahead is air with a floor under it, as in a cave, walks on along the floor instead,
    so it needs no block to build its next step with. Gives result(): ok, no_surface in a dimension with a roof over it
    (surface gives None there), or the reason a step failed with at."""
    step = FACING[facing]
    here = pos()
    if surface(here) is None:
        return result("no_surface", here)
    opened = [here, here.up()]
    while not surfaced(here, step):
        up = here + (step[0], 1, step[2])
        cells = [here.up(2), up, up.up()]
        ahead = here + step
        if lib.find.is_air(ahead) and is_floor(ahead.down()):
            # ponytail: a long cave floor is walked to its end; a pit ahead still needs a block to bridge with
            up = ahead
            cells = [ahead.up()]
        opened = opened + cells
        for p in cells:
            failure = cut(p, here, opened)
            if failure:
                return failure
        r = goto(up)
        if not r:
            return result(r.reason, up)
        here = up
    return result()


def branch(length, every=8, facing="north"):
    """Digs a tunnel two blocks tall length blocks on from the bot's feet towards facing and walks it, mining each ore
    vein in its walls and ceiling and putting a torch on the floor every every blocks while it carries one. Walls off a
    fluid or falling block beside it as lib.shaft does. Gives result(): ok, or the reason it stopped with at."""
    step = FACING[facing]
    stand = pos()
    opened = [stand, stand.up()]
    for i in range(1, length + 1):
        feet = stand + step
        head = feet.up()
        opened = opened + [feet, head]
        for p in (head, feet):
            failure = cut(p, stand, opened)
            if failure:
                return failure
        r = goto(feet)
        if not r:
            return result(r.reason, feet)
        stand = feet
        failure = lib.mine.veins([feet, head], opened, stand)
        if not failure and i % every == 0 and has("torch"):
            # Once the veins are out, so it hangs on no ore it then mines.
            failure = lib.shaft.put_block(feet, "torch", lib.shaft.TORCHES)
        if failure:
            return failure
    return result()
