# Finding and felling trees, for any script: `import lib.trees`, then
# lib.trees.fell(lib.trees.next_tree()).
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.result import result

LOGS = "#minecraft:logs"
SAPLINGS = "#minecraft:saplings"
# The blocks of the item tag antcraft:scaffold_blocks, which walks pillar and bridge with.
# ponytail: a copy of the tag's values, stale if a datapack changes the tag.
SCAFFOLD = ["dirt", "cobblestone", "cobbled_deepslate", "netherrack", "stone", "andesite", "diorite", "granite", "tuff"]


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


def next_tree(radius=32, where=None, remnants=False, around=None):
    """The base of the nearest tree within radius of around (None: the bot) whose base passes where (a function, None:
    any), or None. With remnants, what is left of a tree whose bottom logs were taken (is_remnant) counts as a tree too."""
    for log in scan(LOGS, radius=radius, around=around):
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


def tree_blocks(base):
    """The logs of the tree at base: those of base's block reached from it log by log, diagonal steps too (acacia and
    jungle branches touch at a corner), base first, at most 512."""
    # ponytail: about 30 block() reads a log, some 15 ticks for a 2x2 spruce; a Java sense, as ore_vein, if that proves slow.
    kind = block(base).id
    seen = {base: True}
    todo = [base]
    out = []
    while todo and len(out) < 512:
        p = todo.pop()
        out.append(p)
        for dx in [-1, 0, 1]:
            for dy in [-1, 0, 1]:
                for dz in [-1, 0, 1]:
                    q = p + (dx, dy, dz)
                    if q not in seen:
                        seen[q] = True
                        b = block(q)
                        if b is not None and b.id == kind:
                            todo.append(q)
    return out


def square(base):
    """The four bottom logs of the 2x2 trunk base is one of, or None where base's trunk is one log wide."""
    kind = block(base).id
    for d in [(0, 0, 0), (-1, 0, 0), (0, 0, -1), (-1, 0, -1)]:
        c = base + d
        sq = [c, c.east(), c.south(), c.east().south()]
        if all([block(p).id == kind for p in sq]):
            return sq
    return None


def is_giant(base):
    """Whether the trunk at base is 2x2, as a giant spruce, jungle tree or dark oak's."""
    return square(base) is not None


def reaches(feet, p):
    """Whether a bot with its feet at feet reaches the block at p, as mine measures it: 4 blocks from its eyes, 1.62 above
    its feet, to the block's centre, and not the blocks it stands in."""
    if p == feet or p == feet.up():
        return False
    dx = feet.x - p.x
    dy = feet.y + 1.12 - p.y
    dz = feet.z - p.z
    return dx * dx + dy * dy + dz * dz <= 16


def pillar(spot):
    """How many blocks a bot must place under spot to stand there: the air below it down to the first block, 99 past 16."""
    n = 0
    q = spot.down()
    while block(q).is_air:
        n += 1
        if n > 16:
            return 99
        q = q.down()
    return n


def stands(blocks):
    """Where to stand to mine the logs blocks lists: few spots, together within reach of each log, chosen greedily, each
    the spot that reaches the most logs not yet reached less the blocks placed to stand there, a spot on one of the logs
    first where that is a tie. Spots are the air above and beside the logs. A log no spot reaches is left out."""
    # ponytail: greedy and ~20 instructions a spot and log, some 30 ticks for a 2x2 spruce; a Java sense if that is slow.
    logs = {}
    for p in blocks:
        logs[p] = True
    cost = {}
    for p in blocks:
        for c in [p.up(), p.north(), p.south(), p.east(), p.west()]:
            if c not in logs and c not in cost and block(c).is_air:
                cost[c] = pillar(c)
    cover = {}
    for c in cost:
        cover[c] = [p for p in blocks if reaches(c, p)]
    left = dict(logs)
    out = []
    while left:
        best = None
        score = 0
        for c in cost:
            s = 2 * (len([p for p in cover[c] if p in left]) - cost[c]) + (1 if c.down() in logs else 0)
            if best is None or s > score:
                best, score = c, s
        if best is None or not [p for p in cover[best] if p in left]:
            break
        out.append(best)
        for p in cover[best]:
            if p in left:
                left.pop(p)
    return out


def fell_whole(base, replant=True):
    """Fells the whole tree at base, branches and 2x2 trunk too: stands at each of stands(tree_blocks(base)), the highest
    first so each stands on logs not yet mined, mines the logs it reaches there, the highest first, then takes back the
    scaffold blocks its walks placed around the tree and picks up what fell. With replant, plants a sapling of the tree's
    kind (else any) where each log of the trunk's bottom stood, four for a 2x2 trunk. Assumes the bot holds an axe or goes
    without one, and carries scaffold blocks for a tree taller than it reaches. Gives result(): ok, out_of_reach at a log
    it could not mine, or no_space at one whose drops did not fit."""
    blocks = tree_blocks(base)
    spots = square(base) or [base]
    sapling = block(base).id.replace("_log", "_sapling")
    plan = sorted(stands(blocks), key=lambda s: -s.y)
    left = {}
    for p in blocks:
        left[p] = True
    # What the walks place, under the stands and wherever else they pillar or bridge, is what of SCAFFOLD the bot carries
    # that was not there before.
    ids = [b for b in SCAFFOLD if has(b)]
    radius = min(32, 3 + int(max([base.distance(p) for p in blocks])))
    before = {}
    for q in scan(ids, radius=radius, limit=4096, around=base) if ids else []:
        before[q] = True
    for i, s in enumerate(plan):
        goto(s)  # where it gets nowhere, mine walks for each log
        later = plan[i + 1:]
        # The logs a later stand stands on stay for that stand to mine.
        for p in sorted([p for p in left if reaches(s, p) and p.up() not in later], key=lambda p: -p.y):
            r = mine(p)
            if r or r.reason == "target_gone":
                left.pop(p)
            elif r.reason == "no_space":
                return result("no_space", p)
    for p in sorted(list(left), key=lambda p: -p.y):
        r = mine(p)
        if not r and r.reason != "target_gone":
            return result("no_space" if r.reason == "no_space" else "out_of_reach", p)
    placed = [q for q in (scan(ids, radius=radius, limit=4096, around=base) if ids else []) if q not in before]
    for q in sorted(placed, key=lambda q: -q.y):
        mine(q)
    pick_up([LOGS, SAPLINGS])
    if replant:
        for p in spots:
            if block(p).is_air and "#minecraft:dirt" in block(p.down()).tags:
                if has(sapling):
                    place(p, sapling)
                elif has(SAPLINGS):
                    place(p, SAPLINGS)
    return result()


def logs(n, radius=32, kind=LOGS):
    """Gets logs that kind selects from the nearest trees within radius, one tree at a time, until the bot carries n
    more: fells (and replants) a tree all of whose logs (tree_blocks) it needs with fell_whole, else mines only the bottom
    logs of one trunk column it still needs, so it never climbs and does not walk between trees. What is left of a tree it
    took the bottom of it takes first next time, as next_tree(remnants=True) finds it as a tree. Gives result(): ok,
    no_space where its inventory is full, or no_logs with missing once no tree it can take from is left."""
    # ponytail: a tree it needs only part of it takes a column at a time, so of a 2x2 spruce it may leave columns standing.
    target = count(kind) + n
    skip = {}
    while count(kind) < target:
        base = next_tree(radius, lambda b: b not in skip and is_kind(b, kind), True)
        if base is None:
            return result("no_logs", None, {kind: target - count(kind)})
        before = count(kind)
        column = trunk(base)
        if target - before >= len(column) and target - before >= len(tree_blocks(base)):
            r = fell_whole(base, is_tree(base))
            if r["reason"] == "no_space":
                return r
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
