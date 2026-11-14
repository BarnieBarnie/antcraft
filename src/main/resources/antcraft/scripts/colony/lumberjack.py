# A colony lumberjack: fells the trees within the colony's radius whole, branches and 2x2 trunks too, replants a
# sapling where each stood (four for a 2x2 trunk), and puts the logs, and all else but its tools, food, saplings and fungi,
# into the Colony Core. It weighs an axe against the bare hand for all the colony's logs in sight, not one tree's, and
# climbs on blocks it carries, takes from the core or borrows from a chest, never on dirt dug from the colony's ground. Run it on a colony member with `/antcraft bot run colony.lumberjack`, or set it on a Bot Core
# with `/antcraft core script colony.lumberjack`. Importing colony.common keeps lib.get and lib.trees within the colony.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core

core()  # a bot in no colony says so and stops, before it loads the rest of the library

import lib.find
import lib.stock
import lib.trees
from lib.borrow import give_back
from lib.get import ensure_tool
from lib.inv import store_all
from lib.stock import take_up_to
from lib.trees import LOGS, SCAFFOLD, SEEDLINGS, fell_whole, is_log, next_tree, pillar, sapling, square, stands, tree_blocks, tree_logs


def ready(base):
    """Gets what felling the tree at base takes: an axe where one is cheaper than the bare hand for the colony's logs in
    sight, made from another tree's logs, not this one's; blocks to climb on where it cannot reach every log from the
    ground; and its saplings. Gives the tree's logs and where to stand to fell them, for fell_whole."""
    logs = tree_blocks(base)
    for p in logs:
        lib.trees.KEEP[p] = True
    try:
        # One axe fells the trees around too: their logs count, so a grove of small trees gets one (#553), but not a log
        # wall's or a cabin's (#563). ponytail: the nearest 64 logs within 16 blocks, trees or not, before tree_logs.
        near = tree_logs([p for p in scan(LOGS, radius=16, limit=64) if p not in lib.trees.KEEP and lib.find.allowed(p)])
        # Where it can neither carry, take nor make one, it fells by hand.
        ensure_tool("axe", len(logs) + len(near), "hand")
        # Blocks to climb on: the pillars under every stand fell_whole stands on to reach every log, branches too, a block
        # under two stands counted once, as fell_whole takes none back before the last stand (#596); at least the climb to
        # the highest stand from the ground under base, which is in the air for a remnant (#582), for a stand on a log.
        # ponytail: a stand on a high log away from the pillars is short of its climb; take_back between stands if it matters.
        plan = stands(logs)
        ground = base.y - pillar(base)
        under = {}
        for s in plan:
            for i in range(1, pillar(s) + 1):
                under[s.down(i)] = True
        climb = max([s.y for s in plan] + [ground]) - ground
        n = max(climb, len(under))
        if n > 0:
            scaffold(n)
        kind = sapling(block(base).id)
        if kind:
            # From the chests only, not get: get breaks leaves for saplings, this tree's too (#602), and felling drops some.
            take_up_to(kind, len(square(base) or [base]))
        return logs, plan
    finally:
        lib.trees.KEEP.clear()


def scaffold(n):
    """Carries n blocks of SCAFFOLD, those it has and those of lib.stock's chests, the core's plainly and other chests'
    borrowed (give_back returns them), any kind: it digs no hole in the colony's ground for them (#553, #563). Short of
    them, fell_whole fells what it reaches."""
    chests = None
    for item in SCAFFOLD:
        short = n - count("#antcraft:scaffold_blocks")
        if short <= 0:
            return
        if chests is None:
            chests = lib.stock.chests(16)  # one scan for every kind
        take_up_to(item, count(item) + short, chests=chests)


while True:
    # Remnants too: what lib.trees.logs leaves of a tree it took the bottom logs of for an axe.
    tree = next_tree(remnants=True)
    if tree is None:
        store_all(core(), keep=SEEDLINGS)
        wait(200)
    else:
        try:
            logs, plan = ready(tree)
            fell_whole(tree, blocks=logs, plan=plan)
        finally:
            give_back()
        store_all(core(), keep=SEEDLINGS)
        if is_log(tree):
            wait(200)  # it could not fell this one (protected, out of reach): not again at once
