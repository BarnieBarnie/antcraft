# A colony lumberjack: fells the trees within the colony's radius, replants a
# sapling where each stood, and puts the logs, and all else but its tools, food and
# saplings, into the Colony Core. Run it on a
# colony member with `/antcraft bot run colony.lumberjack`, or set it on a Bot
# Core with `/antcraft core script colony.lumberjack`.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from lib.craft import ensure_tool
from lib.inv import store_all
from colony.common import core, in_colony
from lib.trees import SAPLINGS, is_log, next_tree, fell


core()  # a bot in no colony says so and stops
while True:
    tree = next_tree(where=in_colony)
    if tree is None:
        store_all(core(), keep=[SAPLINGS])
        wait(200)
    else:
        ensure_tool("axe")  # the best carried, else the best in the core, else one made from what it holds; without one it fells by hand
        fell(tree)
        store_all(core(), keep=[SAPLINGS])
        if is_log(tree):
            wait(200)  # it could not fell this one (protected, out of reach): not again at once
