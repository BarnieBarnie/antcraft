# Exploring the world and remembering what is where, for any script: `import lib.explore`, then
# lib.explore.explore(until=lambda s: lib.explore.seen("village")) and lib.explore.report().
# A module of the mod's default library (lib/), a skill: it imports primitives only (docs/design/library.md, Skills).
# What the bot has seen is kept in memory["seen"], {"biomes": {id: [pos, ...]}, "structures": {id: [pos, ...]}}, so it
# survives restarts and adds up over explores.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.retry
from lib.result import result

# A biome is remembered once per CELL by CELL blocks (a chunk), so memory stays small however long the bot explores.
CELL = 16


def spiral(centre, step=32, rings=4):
    """The waypoints of a square spiral around centre, step blocks apart, rings rings out: centre first, then east and
    on clockwise (south, west, north), ring after ring, (2 * rings + 1) ** 2 of them, all at centre's y."""
    centre = Pos(centre)
    out = [centre]
    x = 0
    z = 0
    dx = 1
    dz = 0
    leg = 1
    n = (2 * rings + 1) ** 2
    while len(out) < n:
        for _ in range(2):
            for _ in range(leg):
                x = x + dx
                z = z + dz
                out.append(centre + (x * step, 0, z * step))
            dx, dz = -dz, dx
        leg = leg + 1
    return out[:n]


def memo():
    """memory["seen"], made empty the first time."""
    return memory.get("seen", {"biomes": {}, "structures": {}})


def look(radius=64, around=16):
    """Records the biome at the bot's feet and around blocks north, south, east and west of them, one position per
    biome and CELL, and each structure within radius (structures(radius), at most 128) once, at the centre of its box.
    Takes no ticks; gives memory["seen"]."""
    s = memo()
    here = pos()
    for d in ((0, 0), (around, 0), (-around, 0), (0, around), (0, -around)):
        p = here + (d[0], 0, d[1])
        b = biome(p)
        if b is not None:
            ps = s["biomes"].get(b, [])
            if not any([q.x // CELL == p.x // CELL and q.z // CELL == p.z // CELL for q in ps]):
                ps.append(p)
            s["biomes"][b] = ps
    for t in structures(min(radius, 128)):
        ps = s["structures"].get(t["id"], [])
        if t["at"] not in ps:
            ps.append(t["at"])
        s["structures"][t["id"]] = ps
    memory["seen"] = s
    return s


def explore(rings=4, step=32, until=None):
    """Walks the spiral(pos(), step, rings) and look()s at each waypoint, structures within step and biomes step / 2
    around. A waypoint goes to the surface where its column is loaded; one the bot cannot get to is looked at from
    where the walk stopped. Stops at the first waypoint where until(memory["seen"]) is true. Gives result(): ok and at
    where it stopped; where until never held, not_found."""
    for w in spiral(pos(), step, rings):
        t = surface(w)
        # ponytail: a waypoint not loaded yet keeps the centre's y; goto digs or pillars to it.
        lib.retry.retry(goto, (w if t is None else t,), 3)
        s = look(step, step // 2)
        if until is not None and until(s):
            return result(None, pos())
    return result(None if until is None else "not_found", pos())


def matches(id, what):
    """Whether the biome or structure id is what: the same id, or what, without a namespace, is part of its name, so
    "village" matches every village and "ocean" every ocean. ponytail: no #tags, as no sense reads a biome's tags."""
    return id == what or (":" not in what and what in id.split(":")[-1])


def seen(what):
    """The positions remembered of the biomes and structures what matches (matches()), nearest the bot first."""
    s = memo()
    here = pos()
    out = []
    for kind in ("biomes", "structures"):
        for id, ps in s[kind].items():
            if matches(id, what):
                out = out + ps
    return sorted(out, key=lambda p: here.distance(p))


def report():
    """Prints one line per biome and structure remembered: its id and the position of it nearest the bot."""
    s = memo()
    here = pos()
    for kind in ("biomes", "structures"):
        for id, ps in s[kind].items():
            print(id + " at " + str(min(ps, key=lambda p: here.distance(p))))
