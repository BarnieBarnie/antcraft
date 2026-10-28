# A colony lumberjack: fells the trees within the colony's radius, replants a
# sapling where each stood, and puts the logs into the Colony Core. Run it on a
# colony member with `/antcraft bot run colony.lumberjack`, or set it on a Bot
# Core with `/antcraft core script colony.lumberjack`.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import in_colony, store

LOGS = "#minecraft:logs"
SAPLINGS = "#minecraft:saplings"


def is_log(p):
    b = block(p)
    return b is not None and LOGS in b.tags


def trunk_base(log):
    """The lowest log of the trunk log is in."""
    while is_log(log.down()):
        log = log.down()
    return log


def is_tree(base):
    """A trunk standing on dirt with leaves at its top, so a log wall or a cabin is left alone."""
    if "#minecraft:dirt" not in block(base.down()).tags:
        return False
    top = base
    while is_log(top.up()):
        top = top.up()
    for p in [top.up(), top.north(), top.south(), top.east(), top.west()]:
        if "#minecraft:leaves" in block(p).tags:
            return True
    return False


def next_tree():
    """The base of the nearest tree in the colony, or None."""
    for log in scan(LOGS, radius=32):
        base = trunk_base(log)
        if in_colony(base) and is_tree(base):
            return base
    return None


def fell(base):
    """Mines the trunk from the ground up, picks up what fell and plants a sapling where it stood."""
    p = base
    while is_log(p):
        if not mine(p):
            break
        p = p.up()
    pick_up([LOGS, SAPLINGS])
    if block(base).is_air and has(SAPLINGS):
        place(base, SAPLINGS)


while True:
    tree = next_tree()
    if tree is None:
        store(LOGS)
        wait(200)
    else:
        fell(tree)
        store(LOGS)
        if is_log(tree):
            wait(200)  # it could not fell this one (protected, out of reach): not again at once
