# Finding and felling trees, for any script: `import lib.trees`, then
# lib.trees.fell(lib.trees.next_tree()).
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

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


def next_tree(radius=32, where=None):
    """The base of the nearest tree within radius whose base passes where (a function, None: any), or None."""
    for log in scan(LOGS, radius=radius):
        base = trunk_base(log)
        if (where is None or where(base)) and is_tree(base):
            return base
    return None


def fell(base, replant=True):
    """Mines the trunk from the ground up, picks up what fell and, with replant, plants a sapling where it stood."""
    p = base
    while is_log(p):
        if not mine(p):
            break
        p = p.up()
    pick_up([LOGS, SAPLINGS])
    if replant and block(base).is_air and has(SAPLINGS):
        place(base, SAPLINGS)
