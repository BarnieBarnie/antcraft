# Finding blocks, for any script: `import lib.find`, then lib.find.exposed("#c:ores/iron").
# A module of the mod's default library (lib/), a primitive: finders that take no ticks and give a position or None.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

# How many blocks a finder looks through, nearest first; where WHERE leaves out every one, it looks through 8 times as
# many, and so on, until one passes or none are left within its radius (#552).
LIMIT = 256


def in_colony(p):
    """Whether p lies within the colony's radius of its core, measured across: the edge is a column, so a deep block
    under the colony is in it (#576). False for a bot in no colony."""
    c = colony()
    return c is not None and within(c, Pos(p))


def within(c, p):
    """Whether Pos p lies within colony c's radius of its core, measured across."""
    dx = p.x - c["core"].x
    dz = p.z - c["core"].z
    return dx * dx + dz * dz <= c["radius"] * c["radius"]


# Where the library may look, a function of a Pos (None: anywhere): the finders here, lib.trees.next_tree and
# lib.stock.chests leave out each position it is false for, so lib.get neither takes from nor gathers outside it, nor
# counts on it in a cost. colony_edge by default, so every job a member runs keeps to its colony's edge (#594), checked
# each call, so a bot that joins or leaves a colony mid-run follows (#621).
def colony_edge(p):
    """in_colony(p) for a bot in a colony now, True for any other."""
    c = colony()
    return c is None or within(c, Pos(p))


WHERE = colony_edge


def allowed(p):
    """Whether WHERE lets the library use p."""
    return WHERE is None or WHERE(p)


def found(what, radius, keep, first=False):
    """The blocks what selects within radius, nearest first, that keep(p) and WHERE allow, from the LIMIT nearest;
    where WHERE is set and leaves out each of those, from 8 times as many, and so on (ponytail: a bot far outside WHERE
    reads up to the whole radius, and rereads the nearest each time). With first, only the first one. For colony_edge it
    asks colony() (35 instructions) once, not twice a block (#625)."""
    where = WHERE
    if where == colony_edge:
        c = colony()
        if c is None:
            where = None
        else:
            x, z, r = c["core"].x, c["core"].z, c["radius"] * c["radius"]
            where = lambda p: (p.x - x) * (p.x - x) + (p.z - z) * (p.z - z) <= r
    limit = LIMIT
    done = 0
    while True:
        ps = scan(what, radius=radius, limit=limit)
        out = []
        for p in ps[done:]:
            if (where is None or where(p)) and keep(p):
                out.append(p)
                if first:
                    return out
        if out or where is None or len(ps) < limit:
            return out
        done = limit
        limit = limit * 8


def nearest(what, radius=32, where=None):
    """The nearest block what selects within radius for which where(p) is true (any, where is None) and WHERE allows;
    None if there is none."""
    ps = found(what, radius, lambda p: where is None or where(p), True)
    return ps[0] if ps else None


def is_air(p):
    """Whether the block at p is loaded and air."""
    b = block(p)
    return b is not None and b.is_air


def is_open(p):
    """Whether a block beside p, or above it, is air."""
    return any([is_air(s) for s in (p.north(), p.south(), p.east(), p.west(), p.up())])


def exposed(what, radius=32, skip=None):
    """The nearest block what selects within radius with air beside or above it, at or above the bot's feet first,
    which it mines sideways without digging a pit; else the nearest exposed one below; None if there is none. Leaves out
    the positions in the dict skip and those WHERE leaves out."""
    feet = pos().y
    ps = found(what, radius, lambda p: (skip is None or p not in skip) and is_open(p))
    for p in ps:  # nearest first
        if p.y >= feet:
            return p
    return ps[0] if ps else None
