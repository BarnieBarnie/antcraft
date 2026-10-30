# A colony lumberjack: fells the trees within the colony's radius, replants a
# sapling where each stood, and puts the logs into the Colony Core. Run it on a
# colony member with `/antcraft bot run colony.lumberjack`, or set it on a Bot
# Core with `/antcraft core script colony.lumberjack`.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from lib.craft import ensure_tool
from colony.common import in_colony, store
from lib.trees import LOGS, is_log, next_tree, fell


while True:
    tree = next_tree(where=in_colony)
    if tree is None:
        store(LOGS)
        wait(200)
    else:
        ensure_tool("axe")  # the best carried, else one made from what the core holds; without one it fells by hand
        fell(tree)
        store(LOGS)
        if is_log(tree):
            wait(200)  # it could not fell this one (protected, out of reach): not again at once
