# A colony lumberjack: fells the trees within the colony's radius whole, branches and 2x2 trunks too, replants a
# sapling where each stood (four for a 2x2 trunk), and puts the logs, and all else but its tools, food and saplings,
# into the Colony Core. Run it on a colony member with `/antcraft bot run colony.lumberjack`, or set it on a Bot Core
# with `/antcraft core script colony.lumberjack`. Importing colony.common keeps lib.get and lib.trees within the colony.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core

core()  # a bot in no colony says so and stops, before it loads the rest of the library

import lib.trees
from lib.borrow import give_back
from lib.get import ensure_tool, get
from lib.inv import store_all
from lib.trees import SAPLINGS, fell_whole, is_giant, is_log, next_tree, square, tree_blocks

# A tree whose top log is more than this above its base the bot does not reach from the ground: it climbs on dirt.
REACH = 4


def ready(base):
    """Gets what felling the tree at base takes: an axe for its logs where one is cheaper than the bare hand, made from
    another tree's logs, not this one's; dirt to climb a giant tree or one taller than REACH; and its saplings."""
    logs = tree_blocks(base)
    for p in logs:
        lib.trees.KEEP[p] = True
    try:
        # Where it can neither carry, take nor make one, it fells by hand.
        ensure_tool("axe", len(logs), "hand")
        tall = max([p.y for p in logs]) - base.y + 1
        if tall > REACH or is_giant(base):
            get("dirt", tall)
        get(block(base).id.replace("_log", "_sapling"), len(square(base) or [base]))
    finally:
        lib.trees.KEEP.clear()


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
