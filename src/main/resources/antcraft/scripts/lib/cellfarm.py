# A self-expanding farm of cells, for any farm job: `import lib.cellfarm`, then lib.cellfarm.run(spec, name, key, at,
# steps) with a Step("farm", lib.cellfarm.farm, (spec, name, size, radius)) last (docs/reference/modules/lib/cellfarm.md).
# The farm lays out chunk-aligned cells (lib.cells) with a depot north of them (lib.depot), builds a cell each time it can
# get what one takes, up to the cells that keep growing round its corner, and farms them round after round (#826). What
# a cell is, how it is built, when it is ripe and how it is harvested and tended, the spec says: a dict of hooks.
# A module of the mod's default library (lib/), a kit over lib.cells, lib.depot, lib.project and lib.terrain.
#
# The spec's keys; a cell's hooks take its (low, high) box, low.y its floor:
# - size: a cell's (w, d); categories: the depot's (lib.depot.depot); corners: the key of memory where the farms'
#   corners are kept, by run's key; keep: what lib.depot.store keeps on the bot;
# - materials(): item to count, what the bot carries to build a cell (stocked); build_cell(low, high): builds the cell,
#   once build got its fill and tools, giving result();
# - ripe(low, high): whether the cell has something to harvest; harvest(low, high, told): harvests it, giving result();
#   tend(boxes, told): puts back what the cells lack, such as a planting; most(): the cells at which the bot is busy;
# - optional: gets(item, n, radius) (lib.get.get), fill (dirt) and height (1): flatten's fill item and the blocks over
#   the floor it clears, tools(low, high): tools on top of lib.terrain.needs', spots(low, high): the blocks that must
#   get random ticks ([low, high]), loaded(low, high): whether the bot can read the cell (both corners loaded),
#   room(): empty slots a harvest wants (1), stock(boxes): gets what tending them takes, before the bot walks back to a
#   cell it visits, chores(keep, told): harvests before the round's, giving (harvests, whether the bot is full),
#   switch(): a farm to go on with instead, its result, or None, learn(ticks, n): n (> 0) harvests took ticks, full(boxes,
#   told): what a full farm does each round, idle(boxes): what a round that harvested nothing does before its wait.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.borrow
import lib.cells
import lib.depot
import lib.food
import lib.project
import lib.terrain
import lib.trees
from lib.get import ensure_tool, get
from lib.result import result

WAIT = 100  # ticks between looks at the farm while nothing was ripe
# Rounds the farm lets pass before it tries again to get what the next cell takes, where it could not (#651): a get can
# be a long chain (charcoal, a furnace, cobblestone, a pickaxe). Some 1000 ticks of waits; a harvest tries at once.
BACKOFF = 10
# The chunks round a bot's own that get random ticks, so grow plants (#812): a bot's ticket (BotChunks, radius 3) gives
# its chunk simulation level 30, one more a chunk out, and in 26.3 ServerChunkCache.tickChunks random-ticks only the
# chunks at entity-ticking level (31 or less, ChunkMap.forEachBlockTickingChunk), so the bot's chunk and the ring round it.
TICKING = 1
TICKING_CELLS = {}  # a farm's name to ticking's cells, worked out once a run: a farm's layout does not change


def tell(told, what):
    """Says what once: not where told, a dict of what was said, holds it already."""
    if what not in told:
        told[what] = True
        say(what)


def own():
    """Strikes what the bot borrowed off lib.borrow: the farm keeps what it took for good."""
    for b in lib.borrow.borrowed():
        lib.borrow.keep(b["item"])


def loaded(f, low, high):
    """Whether the bot can read the cell: the spec's loaded, else both its corners are loaded (#810)."""
    if "loaded" in f:
        return f["loaded"](low, high)
    return block(low) is not None and block(high) is not None


def ticking(f, name):
    """The indices of the farm name's cells whose spots get random ticks while the bot is at the farm's corner, the low
    corner of cell 0, where its depot is (#812): a cell farther off grows only while the bot is near it."""
    if name not in TICKING_CELLS:
        TICKING_CELLS[name] = ticking_at(f, name, lib.cells.cell(name, 0)[0])
    return TICKING_CELLS[name]


def ticking_at(f, name, corner):
    """ticking's cells of the farm name were its cell 0's low corner at corner instead: those whose spots all lie in
    corner's chunk or within TICKING of it."""
    low = lib.cells.cell(name, 0)[0]
    d = (corner.x - low.x, 0, corner.z - low.z)
    spots = f.get("spots", lambda a, b: [a, b])
    cx, cz = corner.x // 16, corner.z // 16
    # ponytail: the first 32 cells; 8 rows of 4 of the narrowest wood farm cell (5 and a path) reach past the 2 chunks
    # east and south of the corner's that tick. Some 5 ticks of instructions a corner.
    return [i for i in range(32)
            if all([abs(p.x // 16 - cx) <= TICKING and abs(p.z // 16 - cz) <= TICKING
                    for p in spots(*[p + d for p in lib.cells.cell(name, i)])])]


def hint(f, name, at):
    """Says where a corner fits more cells that keep growing than at, the new farm name's (#813). A corner at the
    north-west block of a chunk (x and z a multiple of 16) fits the most, the same at any chunk, so it counts one such
    corner and names the nearest of those of at's chunk and of the chunks east, south and south-east. The farm keeps at."""
    if at.x % 16 == 0 and at.z % 16 == 0:
        return  # the most already; nothing counted
    x, z = at.x // 16 * 16, at.z // 16 * 16
    best = len(ticking_at(f, name, Pos(x, at.y, z)))
    n = len(ticking(f, name))
    if best > n:
        c = None
        for dx in (0, 16):
            for dz in (0, 16):
                p = Pos(x + dx, at.y, z + dz)
                if c is None or p.distance(at) < c.distance(at):
                    c = p
        say(f"a corner at {c.x} {c.y} {c.z} fits {best} growing cells, this one {n}")


def limit(f, name, size):
    """The most cells of the farm name: size, else the spec's most() but no more than its cells that keep growing
    (ticking, #812)."""
    return size or min(f["most"](), len(ticking(f, name)))


def built(f, name, size):
    """How many cells of the farm name count toward limit: all built, else with no size those built that keep growing
    (ticking), so cells outside them built under a size or before #812 leave its room (#815)."""
    if size:
        return lib.cells.count(name)
    return len([i for i in lib.cells.built(name) if i in ticking(f, name)])


def following(f, name, size):
    """The index of the cell build builds next: lib.cells.next, else with no size the lowest cell that keeps growing not
    built (ticking, #812). Kept as "next" in the farm's memory["cells"][name], so lib.treefarm.outside_cells keeps dirt
    gets off it (#816)."""
    i = lib.cells.next(name)
    if not size:
        i = ([j for j in ticking(f, name) if j not in lib.cells.built(name)] + [i])[0]
    lib.cells.farm(name)["next"] = i
    return i


def build(f, name, size, radius):
    """Builds the next cell of the farm name (following) with the tools lib.terrain.needs for its ground up to the
    spec's height, and the spec's tools, and the fill its floor's holes take on top of the materials' (gets, #821),
    then the spec's build_cell; marks it built and says so. Gives result()."""
    i = following(f, name, size)
    low, high = lib.cells.cell(name, i)
    needs = lib.terrain.needs(low, Pos(high.x, low.y + f.get("height", 1), high.z), low.y)
    fill = f.get("fill", "minecraft:dirt")
    n = needs.get("fill", 0) + f["materials"]().get(fill, 0)
    if n:
        r = f.get("gets", get)(fill, n, radius)
        if not r["ok"]:
            return r
    tools = needs["tools"]
    if "tools" in f:
        for k, uses in f["tools"](low, high).items():
            tools[k] = tools.get(k, 0) + uses
    for k, uses in tools.items():
        r = ensure_tool(k, uses, None if k == "pickaxe" else "hand", radius)
        if not r["ok"]:
            return r
    lib.trees.take_back()  # what earlier walks placed comes down before the cell goes up, as before a harvest (#654)
    r = f["build_cell"](low, high)
    if r["ok"]:
        lib.cells.add(name, i)
        say(f"built cell {i + 1}")
        say(f"cells {built(f, name, size)}/{limit(f, name, size)}")
    return r


def stocked(f, radius):
    """Makes the bot carry the spec's materials() by its gets (#643). Gives result(): ok, or the failed get's for the
    first item it could not get."""
    for item, n in f["materials"]().items():
        r = f.get("gets", get)(item, n, radius)
        if not r["ok"]:
            return r
    return result()


def visit(f, b):
    """Walks to cell b where the bot cannot read it (loaded), as a bot keeps only the chunks within 3 of its own loaded
    and goto loads those on its way (#811)."""
    # ponytail: a goto that fails (botsLoadChunks off) leaves the cell for another round.
    if not loaded(f, b[0], b[1]):
        goto(b[0].up(), 2)


def corner(name):
    """Walks the bot into the chunk of the farm name's corner, cell 0's low corner, where it stands elsewhere (#814), so
    the cells ticking counts get random ticks while it waits: to the floor diagonal into cell 0, kept in that chunk."""
    low = lib.cells.cell(name, 0)[0]
    p = pos()
    if p.x // 16 == low.x // 16 and p.z // 16 == low.z // 16:
        return
    # ponytail: a corner at chunk-local 15 on both axes sends it onto the cell's corner block.
    goto(Pos(min(low.x + 1, low.x // 16 * 16 + 15), low.y + 1, min(low.z + 1, low.z // 16 * 16 + 15)))


def farm(f, name, size, radius):
    """Farms forever. Each round harvests every ripe cell (once the bot has the spec's room(), else it stores first, and
    where it still has too little says so once and harvests nothing, #765), stores what it carries (each round, #766),
    tends the cells, builds the next cell where the farm is below limit and stocked gets what one takes (else says once
    what it lacks, and tries again BACKOFF rounds later, or in the next round that harvested), and where it harvested
    nothing does the spec's idle and waits in its corner's chunk (corner, #814). Each round takes back the scaffold its
    walks placed (lib.trees.take_back) before it waits. The harvest, store and tending go over the cells the bot can
    read where it stands, then over those it reads at one cell it cannot, in turn, which it walks to (visit, #811), so
    the corner's cells are farmed every round (#819)."""
    full = False
    told = {}
    backoff = 0  # rounds until stocked is tried again
    visits = 0  # rounds that looked for a cell out of loaded range, to visit those in turn (#811)
    while True:
        lib.food.upkeep(radius)
        # What the setup steps' walks placed is not the round's; what the round's walks place (the harvest's and the
        # build's own taken back by them, idle, tend, get) comes down at its end (#654).
        placed_scaffold()
        own()
        cells = [lib.cells.cell(name, i) for i in lib.cells.built(name)]
        far = [b for b in cells if not loaded(f, b[0], b[1])]
        visited = far[visits % len(far)] if far else None
        visits += 1
        keep = f["keep"]
        others, full_bot = f["chores"](keep, told) if "chores" in f else (0, False)
        n = 0
        ticks = 0
        boxes = []
        for v in [None] + ([visited] if visited else []):
            if v:
                visit(f, v)
            here = [b for b in cells if b not in boxes and loaded(f, b[0], b[1])]
            boxes += here
            ripe = [b for b in here if f["ripe"](b[0], b[1])]
            start = game_time()
            for b in [] if full_bot else ripe:
                # Not with too few empty slots that the depot cannot empty: the harvest would be dropped (#765).
                r = lib.depot.make_room(name, f["room"]() if "room" in f else 1, keep, radius)
                if not r["ok"]:
                    full_bot = True
                    tell(told, f"inventory full, store: {r['reason']}; felling stops")
                    break
                if f["harvest"](b[0], b[1], told)["ok"]:
                    n += 1
            # Every round, not only one that harvests: what builds up between harvests, such as the dirt flatten and
            # walks dig, goes in the depot too (#766). A store with nothing over keep walks nowhere.
            if not full_bot:
                r = lib.depot.store(name, keep, radius)
                # Once a reason, not every round: a full depot that takes no new chest leaves the harvest carried (#763).
                if not r["ok"] and "store: " + r["reason"] not in told:
                    told["store: " + r["reason"]] = True
                    say(f"store: {r['reason']}, kept {r['missing']}")
            # After the harvest, so a get for the tending can use what it left, such as leaves to break for saplings.
            if "stock" in f:
                f["stock"](here)
            if v:
                visit(f, v)  # again where the store walked away from it
            f["tend"](here, told)
            ticks += game_time() - start
        harvested = n + others > 0
        if "switch" in f:
            r = f["switch"]()
            if r is not None:
                return r
        if harvested:
            if n and "learn" in f:  # not where only the chores harvested: no harvest of its own to learn from (#838)
                f["learn"](ticks, n)
            backoff = 0
        most_cells = limit(f, name, size)
        have = built(f, name, size)
        if full and have < most_cells:
            say(f"cells {have}/{most_cells}")  # it learnt it may grow (#662); "farm full" again once it is
            full = False
        if have < most_cells and backoff:
            backoff -= 1
        elif have < most_cells:
            # Before stocked's gets (#816); a farm not laid out, as a test's with lib.cells stubbed, has no cell to keep.
            if name in memory.get("cells", {}):
                following(f, name, size)
            r = stocked(f, radius)
            if r["ok"]:
                build(f, name, size, radius)
            else:
                tell(told, f"next cell: {r['reason']}, lacks {r['missing']}")
                backoff = BACKOFF
        elif not full:
            say("farm full")
            full = True
        if full and "full" in f:
            f["full"](boxes, told)
        if not harvested:
            if "idle" in f:
                f["idle"](boxes)
            corner(name)
        lib.trees.take_back()
        if not harvested:
            wait(WAIT)


def carry(name, old, radius):
    """Empties the chests old, a moved farm's old depot, into depot name, in as many trips as the bot's room takes, and
    keeps what the bot carried itself (#823). Where a trip takes nothing but the old chests still hold some, such as a
    full depot, it says what is left there and goes on."""
    keep = list(dict([(s.item, count(s.item)) for s in inventory()]).items())
    while True:
        took = 0
        for chest in old:
            for s in container(chest) or []:
                if free_slots():
                    r = take(chest, s.item, s.count)
                    took += r.value if r else 0
        lib.depot.store(name, keep, radius)
        if not took:
            break
    left = sum([s.count for chest in old for s in container(chest) or []])
    if left:
        say(f"left {left} items in the old depot")


def run(f, name, key, at, steps, radius=32):
    """Sets up the farm name with the spec f and runs its steps (lib.project.run), the last of which farms. Its corner
    is at, else the one memory[f["corners"]][key] keeps, else where the bot stands; an at other than the kept one moves
    the farm there, laid out afresh (#818), its old depot's stock carried to the new one (carry, #823). The cells go
    east and south from the block under the corner (lib.cells), the depot north of them, a path's width off, going east;
    home is the path by the depot, so a bot that dies comes back at the farm and lib.trees.recover fetches what it
    dropped (#679). Gives lib.project.run's result() once a step fails, after a line naming the reason and what the
    step lacks."""
    farms = memory.get(f["corners"], {})
    new = key not in farms
    at = Pos(at) if at is not None else farms.get(key, pos())
    moved = not new and at != farms[key]
    farms[key] = at
    memory[f["corners"]] = farms
    old = []
    if moved:
        # The old depot's chests, emptied into the new depot once it is laid out (#823).
        old = lib.depot.chests(name) if name in memory.get("depots", {}) else []
        # A new corner lays the farm out afresh there, its depot and project with it (#818). The old cells and chests
        # are left standing, forgotten, the chests emptied.
        for k in ("cells", "depots"):
            m = memory.get(k, {})
            m.pop(name, None)
            memory[k] = m
        TICKING_CELLS.pop(name, None)
        lib.project.reset(name)
        say(f"farm moved to {at.x} {at.y} {at.z}")
    lib.cells.layout(name, at.down(), f["size"])
    if new or moved:
        hint(f, name, at)
    lib.depot.depot(name, at + (0, 0, -2), "east", f["categories"])
    if old:
        carry(name, old, radius)
    set_home(at + (0, 0, -1))
    lib.trees.recover()
    r = lib.project.run(name, steps)
    lacks = ", ".join([f"{k} x{v}" for k, v in r.get("lacks", {}).items()])
    say(f"{name} stopped: {r['reason']} at {r['at']}" + (f", missing {lacks}" if lacks else ""))
    return r
