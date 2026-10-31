# Finding and felling trees, for any script: `import lib.trees`, then
# lib.trees.fell(lib.trees.next_tree()).
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.shaft import result

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
    return "#minecraft:dirt" in block(base.down()).tags and crowned(base)


def is_remnant(base):
    """A trunk over air with leaves at its top: what is left of a tree logs took the bottom logs of."""
    return block(base.down()).is_air and crowned(base)


def crowned(base):
    """Whether the trunk with its lowest log at base has leaves at its top."""
    top = base
    while is_log(top.up()):
        top = top.up()
    for p in [top.up(), top.north(), top.south(), top.east(), top.west()]:
        if "#minecraft:leaves" in block(p).tags:
            return True
    return False


def next_tree(radius=32, where=None, remnants=False):
    """The base of the nearest tree within radius whose base passes where (a function, None: any), or None. With
    remnants, what is left of a tree whose bottom logs were taken (is_remnant) counts as a tree too."""
    for log in scan(LOGS, radius=radius):
        base = trunk_base(log)
        if (where is None or where(base)) and (is_tree(base) or remnants and is_remnant(base)):
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


def low_log(radius=32, skip=None):
    """The lowest log within radius not in skip (a dict of positions), the nearest among those, or None."""
    here = pos()
    logs = [p for p in scan(LOGS, radius=radius, limit=256) if skip is None or p not in skip]
    if not logs:
        return None
    pairs = sorted([(p.y, p.distance(here), i) for i, p in enumerate(logs)])
    return logs[pairs[0][2]]


def is_kind(p, kind):
    """Whether the block at p is what kind selects: a block id such as "oak_log" or a tag such as "#minecraft:logs"."""
    b = block(p)
    if b is None:
        return False
    if kind.startswith("#"):
        return kind in b.tags
    return b.id == (kind if ":" in kind else "minecraft:" + kind)


def trunk(base):
    """The logs straight up from base of the same block as base, base first."""
    logs = []
    p = base
    while block(p).id == block(base).id:
        logs.append(p)
        p = p.up()
    return logs


def logs(n, radius=32, kind=LOGS):
    """Gets logs that kind selects from the nearest trees within radius, one tree at a time, until the bot carries n
    more: fells (and replants) a tree whose whole trunk it needs, else mines only the bottom logs it still needs, so it
    never climbs and does not walk between trees. What is left of a tree it took the bottom of it takes first next time,
    as next_tree(remnants=True) finds it as a tree. Gives result(): ok, no_space where its inventory is full, or no_logs
    with missing once no tree it can take from is left."""
    # ponytail: one column of trunk is one tree, so a 2x2 spruce is four trees side by side, each a step from the last.
    target = count(kind) + n
    skip = {}
    while count(kind) < target:
        base = next_tree(radius, lambda b: b not in skip and is_kind(b, kind), True)
        if base is None:
            return result("no_logs", None, {kind: target - count(kind)})
        before = count(kind)
        column = trunk(base)
        if target - before >= len(column):
            fell(base, is_tree(base))
        else:
            for p in column[:target - before]:
                r = mine(p)
                if not r:
                    if r.reason == "no_space":
                        return result("no_space", p)
                    break
            pick_up(LOGS)
        if count(kind) == before:
            skip[base] = True  # out of reach or stuck: try another tree
    return result()
