# Finding and felling trees, for any script: `import lib.trees`, then
# lib.trees.fell(lib.trees.next_tree()).
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.find
from lib.result import result

LOGS = "#minecraft:logs"
SAPLINGS = "#minecraft:saplings"
# The blocks of the item tag antcraft:scaffold_blocks, which walks pillar and bridge with.
SCAFFOLD = tag_items("#antcraft:scaffold_blocks")


# The vanilla logs whose sapling is no _sapling: the item that grows each.
SAPLING = {"minecraft:mangrove_log": "minecraft:mangrove_propagule", "minecraft:crimson_stem": "minecraft:crimson_fungus",
           "minecraft:warped_stem": "minecraft:warped_fungus"}


def sapling(log):
    """The item that grows the tree of log, a block id: SAPLING's, else its _log made _sapling where that is in
    #minecraft:saplings, else None."""
    s = log.replace("_log", "_sapling")
    return SAPLING.get(log) or (s if s in tag_items(SAPLINGS) else None)


def is_log(p):
    b = block(p)
    return b is not None and LOGS in b.tags


def trunk_base(log):
    """The lowest log of the trunk log is in."""
    while is_log(log.down()):
        log = log.down()
    return log


# What a tree stands on: dirt, or nylium for a huge fungus (#563).
GROUND = ["#minecraft:dirt", "#minecraft:nylium"]
# What crowns a tree: leaves, or a huge fungus's wart blocks (#563).
CROWN = ["#minecraft:leaves", "#minecraft:wart_blocks"]


def on_ground(p):
    """Whether the block at p is GROUND, where a tree grows."""
    tags = block(p).tags
    return any([t in tags for t in GROUND])


def is_tree(base):
    """A trunk standing on GROUND with a CROWN at its top, so a log wall or a cabin is left alone."""
    return on_ground(base.down()) and crowned(base)


def is_remnant(base):
    """A trunk over air with a CROWN at its top, and no log of its block under it at a corner (so no branch): what is
    left of a tree logs took the bottom logs of."""
    return block(base.down()).is_air and below(base) is None and crowned(base)


# The block under one, then the eight under its edges and corners; and those over one.
UNDER = [(0, -1, 0)] + [(dx, -1, dz) for dx in [-1, 0, 1] for dz in [-1, 0, 1] if dx or dz]
OVER = [(0, 1, 0)] + [(dx, 1, dz) for dx in [-1, 0, 1] for dz in [-1, 0, 1] if dx or dz]


def below(p):
    """The block of p's kind under p, straight under first, else at an edge or corner under it (an acacia's trunk bends
    so), or None."""
    return _step(p, UNDER)


def _step(p, ds):
    kind = block(p).id
    for d in ds:
        q = p + d
        b = block(q)
        if b is not None and b.id == kind:
            return q
    return None


def crowned(base):
    """Whether the trunk with its lowest log at base has a CROWN at its top: the top reached log by log up, straight up
    first, else at an edge or corner over it, as an acacia's trunk bends."""
    top = base
    while True:
        q = _step(top, OVER)
        if q is None:
            break
        top = q
    for p in [top.up(), top.north(), top.south(), top.east(), top.west()]:
        tags = block(p).tags
        if any([t in tags for t in CROWN]):
            return True
    return False


def tree_logs(logs):
    """The logs of logs (a list of positions) that are part of a tree: whose trunk, followed down log by log (below),
    stands as a tree (is_tree) or is what is left of one (is_remnant). A log wall or a cabin's logs are left out."""
    # Each log down to its base is read once: those passed on the way share the answer.
    tree = {}
    out = []
    for p in logs:
        if p not in tree:
            path = [p]
            q = below(p)
            while q is not None and q not in tree:
                path.append(q)
                q = below(q)
            base = path[-1]
            t = tree[q] if q is not None else (is_tree(base) or is_remnant(base))
            for r in path:
                tree[r] = t
        if tree[p]:
            out.append(p)
    return out


def next_tree(radius=32, where=None, remnants=False, around=None):
    """The base of the nearest tree within radius of around (None: the bot) whose base passes where (a function, None:
    any) and lib.find.WHERE, or None. With remnants, what is left of a tree whose bottom logs were taken (is_remnant) counts as a tree too."""
    # Only a log with no log under it is a base, so each trunk costs one is_tree. A scan reads the whole sphere unless it
    # reaches its limit, from a block budget every bot shares (some 2,000 blocks at radius 8, 137,000 at 32), so it looks
    # within 8 blocks first, then 16, then radius (#527), and at the nearest 64 logs first: only where all 64 are no tree
    # does it look again, up to 4096, past a forest's nearer logs (#520). Each scan's logs begin with the last one's, as
    # every scan reads blocks in the same order, so only the new ones are checked.
    done = 0
    r = 8 if radius > 8 else radius
    limit = 64
    while True:
        found = scan(LOGS, radius=r, limit=limit, around=around)
        for base in found[done:]:
            if not is_log(base.down()) and (where is None or where(base)) and lib.find.allowed(base) and (is_tree(base) or remnants and is_remnant(base)):
                return base
        done = len(found)
        if len(found) == limit:
            if limit == 4096:
                return None
            limit = 4096
        elif r == radius:
            return None
        else:
            r = radius if r * 2 > radius else r * 2


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
    """The lowest log within 8 blocks, else 16, else radius, not in skip (a dict of positions) nor KEEP, the nearest among
    those, or None."""
    # Near first, as next_tree does (#527, #532): a log by the bot costs some 2,000 blocks, not the whole sphere.
    here = pos()
    r = 8 if radius > 8 else radius
    while True:
        logs = [p for p in scan(LOGS, radius=r, limit=256) if (skip is None or p not in skip) and p not in KEEP]
        if logs:
            pairs = sorted([(p.y, p.distance(here), i) for i, p in enumerate(logs)])
            return logs[pairs[0][2]]
        if r == radius:
            return None
        r = radius if r * 2 > radius else r * 2


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


# The 26 blocks around one, faces, edges and corners.
AROUND = [(dx, dy, dz) for dx in [-1, 0, 1] for dy in [-1, 0, 1] for dz in [-1, 0, 1] if dx or dy or dz]


def tree_blocks(base):
    """The logs of the tree at base: those of base's block reached from it log by log, diagonal steps too (acacia and
    jungle branches touch at a corner), base first, at most 512."""
    # Some 580 instructions a log (a block() read is 30): 29 ticks of a bot's budget for a jungle giant of 100 logs (#522).
    kind = block(base).id
    seen = {base: True}
    todo = [base]
    out = []
    while todo and len(out) < 512:
        p = todo.pop()
        out.append(p)
        for d in AROUND:
            q = p + d
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
    # Greedy. A spot reaches only logs 2 below to 5 above its feet, so it tries those layers alone, and counts what each
    # spot still reaches as logs are reached rather than again each round: for a jungle giant of 100 logs (232 spots)
    # some 450,000 instructions, 225 ticks of a bot's budget, down from 1,700,000 (#522).
    # ponytail: felling that tree takes thousands of ticks, so a Java sense only if bigger trees make this slow.
    logs = {}
    layers = {}
    for p in blocks:
        logs[p] = True
        layers.setdefault(p.y, []).append(p)
    cost = {}
    for p in blocks:
        for c in [p.up(), p.north(), p.south(), p.east(), p.west()]:
            if c not in logs and c not in cost and block(c).is_air:
                cost[c] = None
    # Lowest first, so a spot over another (air) has one more to place than that one, without reading down again.
    for c in sorted(list(cost), key=lambda c: c.y):
        under = cost.get(c.down())
        cost[c] = pillar(c) if under is None else (99 if under >= 16 else under + 1)
    cover = {}
    left = {}  # each log not yet reached: the spots that reach it
    for p in blocks:
        left[p] = []
    n = {}
    bonus = {}
    for c in cost:
        x, z, up = c.x, c.z, c.up()
        reached = []
        for y in range(c.y - 2, c.y + 6):
            if y in layers:
                dy = c.y + 1.12 - y
                r = 16 - dy * dy
                for p in layers[y]:
                    dx = x - p.x
                    dz = z - p.z
                    if dx * dx + dz * dz <= r and p != up:  # as reaches(c, p); p is a log, so not c
                        reached.append(p)
                        left[p].append(c)
        cover[c] = reached
        n[c] = len(reached)
        bonus[c] = 1 if c.down() in logs else 0
    out = []
    while left:
        best = None
        score = 0
        for c in cost:
            # Only a spot that reaches a log left: one that reaches none on bare ground (score 0) beat a pillar to the
            # top of a tree too tall to reach from the ground, so that tree got no stand by its top (#563).
            if n[c]:
                s = 2 * (n[c] - cost[c]) + bonus[c]
                if best is None or s > score:
                    best, score = c, s
        if best is None:
            break
        out.append(best)
        for p in cover[best]:
            if p in left:
                for c in left.pop(p):
                    n[c] -= 1
    return out


def take_back():
    """Mines the scaffold the script's walks placed since it last asked placed_scaffold(), the highest first: only blocks
    unchanged since, so one someone else mined or put there meanwhile stays."""
    for q in sorted(placed_scaffold(), key=lambda q: -q.y):
        mine(q)


def fell_whole(base, replant=True, blocks=None, plan=None):
    """Fells the whole tree at base, branches and 2x2 trunk too: stands at each of stands(tree_blocks(base)), the highest
    first so each stands on logs not yet mined, mines the logs it reaches there, the highest first, then takes back the
    scaffold blocks its walks placed around the tree and picks up what fell. With replant, plants a sapling of the tree's
    kind (else any) where each log of the trunk's bottom stood on GROUND, four for a 2x2 trunk: a fungus on nylium too.
    blocks and plan, where given, are tree_blocks(base) and stands(blocks), so a caller that worked them out already
    does not again. Assumes the bot holds an axe or goes
    without one, and carries scaffold blocks for a tree taller than it reaches. Gives result(): ok, out_of_reach at a log
    it could not mine, or no_space at one whose drops did not fit; it takes back its scaffold either way."""
    blocks = blocks or tree_blocks(base)
    spots = square(base) or [base]
    kind = sapling(block(base).id)
    plan = sorted(stands(blocks) if plan is None else plan, key=lambda s: -s.y)
    left = {}
    for p in blocks:
        left[p] = True
    # What the walks place, under the stands and wherever else they pillar or bridge, placed_scaffold() gives (#533); what
    # earlier walks placed is not this tree's to take back.
    placed_scaffold()
    for i, s in enumerate(plan):
        goto(s)  # where it gets nowhere, mine walks for each log
        later = plan[i + 1:]
        # The logs a later stand stands on stay for that stand to mine.
        for p in sorted([p for p in left if reaches(s, p) and p.up() not in later], key=lambda p: -p.y):
            r = mine(p)
            if r or r.reason == "target_gone":
                left.pop(p)
            elif r.reason == "no_space":
                take_back()
                return result("no_space", p)
    for p in sorted(list(left), key=lambda p: -p.y):
        r = mine(p)
        if not r and r.reason != "target_gone":
            take_back()
            return result("no_space" if r.reason == "no_space" else "out_of_reach", p)
    take_back()
    pick_up([LOGS, SAPLINGS])
    if replant:
        for p in spots:
            if block(p).is_air and on_ground(p.down()):
                if kind and has(kind):
                    place(p, kind)
                elif has(SAPLINGS):
                    place(p, SAPLINGS)
    return result()


# Positions logs leaves alone: a script that means to fell a tree itself puts its logs here while it gets other things.
KEEP = {}


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
        base = next_tree(radius, lambda b: b not in skip and b not in KEEP and is_kind(b, kind), True)
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
