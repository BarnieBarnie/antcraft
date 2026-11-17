# A job: keeps animals in a fenced pen until a player stops it, for meat, leather, wool, eggs and milk. Run it as
# /antcraft bot run jobs.ranch x1 y1 z1 x2 y2 z2 [cap [radius]]: the pen's inside from one corner to the other (its floor
# level y1), the most adults of each kind it keeps (CAP by default) and how far to look for chests (32 by default). Each
# round, for cows, sheep, pigs and chickens, it lures one of a kind that is outside the pen in with its food while the
# kind is under the cap, kills the adults over it (attack; babies never), breeds pairs with their food (use), shears the
# sheep and milks the cows with the buckets in the chests; then it picks up what lies in the pen, such as eggs, and stores
# the produce in a depot of chests three blocks north of the pen's low corner, going east (docs/cookbook/ranch.md, #837,
# #843). Food comes from the chests of the bot's other depots first, such as its crop farm's, then from those within
# radius. The pen, its fence and gate are the player's; the bot walks through the gate. Another job runs it with
# run(low, high); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.borrow
import lib.depot
import lib.stock

NAME = "ranch"
# Each kind and the food that breeds it.
FOOD = {"minecraft:cow": "minecraft:wheat", "minecraft:sheep": "minecraft:wheat",
        "minecraft:pig": "minecraft:carrot", "minecraft:chicken": "minecraft:wheat_seeds"}
SHEARS = "minecraft:shears"
BUCKET = "minecraft:bucket"
WEAPONS = ["#minecraft:axes", "#minecraft:swords"]  # an axe first: a sword's sweep hurts the babies beside
GATES = "#minecraft:fence_gates"
# ponytail: 4 adults of a kind is a guess; measure how many a bot keeps up with (#843).
CAP = 4
REST = 100  # ticks between rounds: a parent breeds again after 6000, a baby grows up in 24000
LURE = 300  # ticks the bot waits in the pen for a lured animal to come in
PRODUCE = ["minecraft:beef", "minecraft:porkchop", "minecraft:mutton", "minecraft:chicken", "minecraft:leather",
           "#minecraft:wool", "minecraft:feather", "minecraft:egg", "minecraft:milk_bucket"]
KEEP = list(FOOD.values()) + [SHEARS, BUCKET] + WEAPONS


def inside(p, low, high):
    """Whether the block p is in the pen from low to high, a block of leeway up and down."""
    return low.x <= p.x <= high.x and low.z <= p.z <= high.z and low.y - 1 <= p.y <= high.y + 1


def middle(low, high):
    """The middle of the pen's floor."""
    return Pos((low.x + high.x) // 2, low.y, (low.z + high.z) // 2)


def herd(kind, low, high):
    """The animals of kind in the pen."""
    return [e for e in entities(kind, 32) if inside(e.pos, low, high)]


def supplies(item, radius):
    """The chests to take item from: those of the bot's other depots (lib.depot), such as its crop farm's, however far,
    nearest first, then those within radius."""
    depots = []
    for name, d in memory.get("depots", {}).items():
        if name != NAME:
            depots += [c[1] for c in d["chests"] if lib.stock.holds(c[1], item)]
    return sorted(depots, key=lib.stock.distance) + lib.stock.where(item, radius)


def take(item, n, radius):
    """Takes up to n of item from the chests (supplies) where the bot carries fewer, and keeps them (lib.borrow.keep)."""
    if count(item) < n:
        lib.stock.take_up_to(item, n, radius, supplies(item, radius))
        lib.borrow.keep(item)
    return count(item) >= n


def gate(low, high):
    """The pen's gate: a fence gate in the fence round low to high, or None."""
    m = middle(low, high)
    for g in scan(GATES, radius=min(32, int(m.distance(high)) + 2), around=m):
        if inside(g, low - (1, 0, 1), high + (1, 0, 1)) and not inside(g, low, high):
            return g
    return None


def lure(kind, animals, low, high, cap, radius):
    """Leads one animal of kind from outside the pen in, while the kind is under cap: with its food in hand (an animal
    follows a bot holding its food, as it does a player) the bot walks to it, then to the block outside the gate, so it
    follows there, opens the gate and walks to the pen's corner farthest from the gate. There it waits until the animal
    is in, two blocks clear of the gate, or LURE ticks have gone by; then it shuts the gate. An animal does not find its
    way round a fence to a bot on the other side, so the bot leads it to the gate first."""
    strays = [e for e in entities(kind, min(radius, 64)) if not inside(e.pos, low, high)]
    if len(animals) >= cap or not strays:
        return
    g = gate(low, high)
    if g is None or not take(FOOD[kind], 1, radius):
        return
    stray = strays[0]
    equip(FOOD[kind])
    goto(stray.pos, near=3)
    out = Pos(g.x + (1 if g.x > high.x else -1 if g.x < low.x else 0), g.y, g.z + (1 if g.z > high.z else -1 if g.z < low.z else 0))
    goto(out)
    # ponytail: the gate stands open while the stray comes in, so another animal may walk out; it is lured back later.
    if not block(g).state["open"]:
        use(g)  # the bot's walks shut only the gates they opened, so this one stays open
    corners = [Pos(x, low.y, z) for x in (low.x, high.x) for z in (low.z, high.z)]
    goto(max(corners, key=lambda c: c.distance(g)))
    for _ in range(LURE // 10):
        p = stray.pos
        if not stray.valid or inside(p, low, high) and max(abs(p.x - g.x), abs(p.z - g.z)) >= 2:
            break
        wait(10)
    if block(g).state["open"]:
        use(g)


def cull(adults, cap, radius):
    """Kills the adults past the first cap, with an axe or sword where the bot has or can take one."""
    if len(adults) <= cap:
        return
    if not any([equip(w) for w in WEAPONS]):
        take(WEAPONS[0], 1, radius)
        any([equip(w) for w in WEAPONS])
    for a in adults[cap:]:
        if a.valid and not a.baby:
            attack(a)
    pick_up(radius=8)


def breed(kind, animals, adults, cap, radius):
    """Feeds pairs of adults while the kind would stay within cap: each pair makes one baby."""
    pairs = min(len(adults) // 2, cap - len(animals))
    food = FOOD[kind]
    if pairs > 0 and take(food, 2 * pairs, radius):
        for a in adults[:2 * pairs]:
            use(a, food)


def shear(adults, radius):
    """Shears each sheep with wool, where the bot has or can take shears."""
    if adults and take(SHEARS, 1, radius):
        for s in adults:
            use(s, SHEARS)  # blocked on a sheared one
        pick_up(radius=8)


def milk(adults, radius):
    """Milks each cow into an empty bucket from the chests, so the buckets a player puts there set how much milk."""
    for c in adults:
        if not take(BUCKET, 1, radius):
            return
        use(c, BUCKET)


def collect(low, high):
    """Picks up the produce that lies in the pen or its fence, such as the eggs the chickens lay: one laid by the fence
    can roll into the fence's block."""
    if [i for i in items_near(PRODUCE, 32) if inside(i.pos, low - (1, 0, 1), high + (1, 0, 1))]:
        m = middle(low, high)
        goto(m)
        pick_up(PRODUCE, radius=min(16, int(m.distance(high)) + 2))


def tend(low, high, cap, radius):
    """One round over each kind, then picks up what lies in the pen and stores the produce."""
    for kind in FOOD:
        lure(kind, herd(kind, low, high), low, high, cap, radius)
        animals = herd(kind, low, high)
        adults = [a for a in animals if not a.baby]
        cull(adults, cap, radius)
        breed(kind, animals, adults[:cap], cap, radius)
        if kind == "minecraft:sheep":
            shear(adults[:cap], radius)
        if kind == "minecraft:cow":
            milk(adults[:cap], radius)
    collect(low, high)
    lib.depot.store(NAME, KEEP, radius)


def run(low, high, cap=CAP, radius=32):
    """Keeps the pen from low to high (its inside, any two opposite corners) round after round. Never returns."""
    low, high = Pos(min(low.x, high.x), min(low.y, high.y), min(low.z, high.z)), Pos(max(low.x, high.x), max(low.y, high.y), max(low.z, high.z))
    lib.depot.depot(NAME, low + (0, 0, -3), "east", {"produce": PRODUCE})
    while True:
        tend(low, high, cap, radius)
        wait(REST)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])), Pos(int(args[3]), int(args[4]), int(args[5])),
        int(args[6]) if len(args) > 6 else CAP, int(args[7]) if len(args) > 7 else 32)
