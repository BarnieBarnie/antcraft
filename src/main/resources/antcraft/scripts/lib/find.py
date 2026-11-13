# Finding blocks, for any script: `import lib.find`, then lib.find.exposed("#c:ores/iron").
# A module of the mod's default library (lib/), a primitive: finders that take no ticks and give a position or None.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

# How many blocks a finder looks through, nearest first.
LIMIT = 256


def nearest(what, radius=32, where=None):
    """The nearest block what selects within radius for which where(p) is true (any, where is None); None if there is
    none."""
    for p in scan(what, radius=radius, limit=LIMIT):
        if where is None or where(p):
            return p
    return None


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
    the positions in the dict skip."""
    feet = pos().y
    found = [p for p in scan(what, radius=radius, limit=LIMIT) if (skip is None or p not in skip) and is_open(p)]
    for p in found:  # nearest first
        if p.y >= feet:
            return p
    return found[0] if found else None
