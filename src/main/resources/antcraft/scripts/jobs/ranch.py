# A job: keeps animals in a fenced pen until a player stops it, for meat, leather and wool. Run it as
# /antcraft bot run jobs.ranch x1 y1 z1 x2 y2 z2 [cap [radius]]: the pen's inside from one corner to the other (its floor
# level y1), the most adults of each kind it keeps (CAP by default) and how far to look for chests (32 by default). Each
# round it breeds pairs of cows, sheep, pigs and chickens with their food (use) while a kind is under the cap, kills the
# adults over it (attack; babies never), shears the sheep, picks up the drops and stores them in a depot of chests
# three blocks north of the pen's low corner, going east (docs/cookbook/ranch.md, #837). The pen, its fence and gate
# are the player's; the bot walks through the gate. Another job runs it with run(low, high); imported, this runs nothing.
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
WEAPONS = ["#minecraft:axes", "#minecraft:swords"]  # an axe first: a sword's sweep hurts the babies beside
CAP = 4
REST = 100  # ticks between rounds: a parent breeds again after 6000, a baby grows up in 24000
PRODUCE = ["minecraft:beef", "minecraft:porkchop", "minecraft:mutton", "minecraft:chicken", "minecraft:leather",
           "#minecraft:wool", "minecraft:feather", "minecraft:egg"]
KEEP = list(FOOD.values()) + [SHEARS] + WEAPONS


def inside(p, low, high):
    """Whether the block p is in the pen from low to high, a block of leeway up and down."""
    return low.x <= p.x <= high.x and low.z <= p.z <= high.z and low.y - 1 <= p.y <= high.y + 1


def herd(kind, low, high):
    """The animals of kind in the pen."""
    return [e for e in entities(kind, 32) if inside(e.pos, low, high)]


def take(item, n, radius):
    """Takes up to n of item from the chests where the bot carries fewer, and keeps them (lib.borrow.keep)."""
    if count(item) < n:
        lib.stock.take_up_to(item, n, radius)
        lib.borrow.keep(item)
    return count(item) >= n


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


def tend(low, high, cap, radius):
    """One round over each kind, then stores the produce."""
    for kind in FOOD:
        animals = herd(kind, low, high)
        adults = [a for a in animals if not a.baby]
        cull(adults, cap, radius)
        breed(kind, animals, adults[:cap], cap, radius)
        if kind == "minecraft:sheep":
            shear(adults[:cap], radius)
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
