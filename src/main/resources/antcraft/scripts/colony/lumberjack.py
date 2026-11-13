# A colony lumberjack: fells the trees within the colony's radius whole, branches and 2x2 trunks too, replants a
# sapling where each stood (four for a 2x2 trunk), and puts the logs, and all else but its tools, food and saplings,
# into the Colony Core. It weighs an axe against the bare hand for all the colony's logs in sight, not one tree's, and
# climbs on blocks it carries or takes from the core, never on dirt dug from the colony's ground. Run it on a colony member with `/antcraft bot run colony.lumberjack`, or set it on a Bot Core
# with `/antcraft core script colony.lumberjack`. Importing colony.common keeps lib.get and lib.trees within the colony.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core

core()  # a bot in no colony says so and stops, before it loads the rest of the library

import lib.find
import lib.trees
from lib.borrow import give_back
from lib.get import ensure_tool, get
from lib.inv import store_all
from lib.stock import take_up_to
from lib.trees import LOGS, SAPLINGS, SCAFFOLD, fell_whole, is_log, next_tree, sapling, square, stands, tree_blocks


def ready(base):
    """Gets what felling the tree at base takes: an axe where one is cheaper than the bare hand for the colony's logs in
    sight, made from another tree's logs, not this one's; blocks to climb on where it cannot reach every log from the
    ground; and its saplings."""
    logs = tree_blocks(base)
    for p in logs:
        lib.trees.KEEP[p] = True
    try:
        # One axe fells the trees around too: their logs count, so a grove of small trees gets one (#553).
        # ponytail: the nearest 64 logs within 16 blocks, a log wall too; next_tree's trees if that buys a needless axe.
        near = [p for p in scan(LOGS, radius=16, limit=64) if p not in lib.trees.KEEP and lib.find.allowed(p)]
        # Where it can neither carry, take nor make one, it fells by hand.
        ensure_tool("axe", len(logs) + len(near), "hand")
        # The highest place fell_whole stands to reach every log, branches too: that many blocks to climb on.
        climb = max([s.y for s in stands(logs)] + [base.y]) - base.y
        if climb > 0:
            scaffold(climb)
        kind = sapling(block(base).id)
        if kind:
            get(kind, len(square(base) or [base]))
    finally:
        lib.trees.KEEP.clear()


def scaffold(n):
    """Carries n blocks of SCAFFOLD, those it has and the core's, any kind: it digs no hole in the colony's ground for them
    (#553). Short of them, fell_whole fells what it reaches."""
    for item in SCAFFOLD:
        short = n - count("#antcraft:scaffold_blocks")
        if short <= 0:
            return
        take_up_to(item, count(item) + short, chests=[core()])


while True:
    # Remnants too: what lib.trees.logs leaves of a tree it took the bottom logs of for an axe.
    tree = next_tree(remnants=True)
    if tree is None:
        store_all(core(), keep=[SAPLINGS])
        wait(200)
    else:
        try:
            ready(tree)
            fell_whole(tree)
        finally:
            give_back()
        store_all(core(), keep=[SAPLINGS])
        if is_log(tree):
            wait(200)  # it could not fell this one (protected, out of reach): not again at once
