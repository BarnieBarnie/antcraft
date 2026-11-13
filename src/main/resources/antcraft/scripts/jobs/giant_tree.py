# A job: fells the giant tree (a 2x2 trunk: jungle, spruce or dark oak) nearest to a position, or to the bot, and replants
# it. Run it as /antcraft bot run jobs.giant_tree [x y z] [radius] (docs/design/library.md, Example jobs).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.trees
from lib.borrow import borrowed, give_back
from lib.get import ensure_tool, get
from lib.result import result

at = Pos(int(ARGS[0]), int(ARGS[1]), int(ARGS[2])) if len(ARGS) >= 3 else pos()
radius = int(ARGS[-1]) if len(ARGS) in (1, 4) else 32


def fell():
    """Fells the giant tree nearest to at with an axe that lasts, scaffold dirt and saplings; gives result()."""
    base = lib.trees.next_tree(radius, lib.trees.is_giant, around=at)
    if base is None:
        return result("no_tree", at)
    logs = lib.trees.tree_blocks(base)
    r = ensure_tool("axe", len(logs), None, radius)
    if not r["ok"]:
        return r
    # Dirt to climb as high as the tree, and saplings to replant; where there are none it replants nothing.
    r = get("dirt", max([p.y for p in logs]) - base.y + 1, radius)
    if not r["ok"]:
        return r
    get(block(base).id.replace("_log", "_sapling"), 4, radius)
    before = count(lib.trees.LOGS)
    r = lib.trees.fell_whole(base)
    if r["ok"]:
        print(f"felled a giant tree: {count(lib.trees.LOGS) - before} logs")
    return r


try:
    r = fell()
    if not r["ok"]:
        warn("giant tree:", r["reason"], r["at"], r["missing"])
finally:
    give_back()
    if borrowed():
        warn("could not give back", borrowed())
