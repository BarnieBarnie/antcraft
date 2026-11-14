# Fell a giant tree

A job that fells a whole giant tree, a 2x2 trunk of jungle, spruce or dark oak, and replants it. The bot need not own an axe or scaffold blocks: it takes them from what is around and gives back what it borrowed. The mod bundles it as `jobs/giant_tree.py`; start it by a giant tree with `/antcraft bot run jobs.giant_tree`, or give the tree's position: `/antcraft bot run jobs.giant_tree 120 64 -35`. A fourth word, or a single one, is how far to look, 32 blocks by default.

```python
import lib.trees
from lib.borrow import borrowed, give_back
from lib.get import ensure_tool, get
from lib.result import result
from lib.stock import take_up_to

def fell(at, radius):
    """Fells the giant tree nearest to at with an axe that lasts, scaffold dirt and saplings; gives result()."""
    base = lib.trees.next_tree(radius, lib.trees.is_giant, around=at)
    if base is None:
        return result("no_tree", at)
    logs = lib.trees.tree_blocks(base)
    # The logs for an axe come from another tree, not this one (#520).
    for p in logs:
        lib.trees.KEEP[p] = True
    try:
        r = ensure_tool("axe", len(logs), None, radius)
        if not r["ok"]:
            return r
        # Dirt to climb as high as the tree, and saplings to replant; where there are none it replants nothing.
        r = get("dirt", max([p.y for p in logs]) - base.y + 1, radius)
        if not r["ok"]:
            return r
        # From chests only: get would break the leaves of this very tree for them (#602).
        take_up_to(block(base).id.replace("_log", "_sapling"), 4, radius)
    finally:
        lib.trees.KEEP.clear()
    before = count(lib.trees.LOGS)
    r = lib.trees.fell_whole(base)
    if r["ok"]:
        print(f"felled a giant tree: {count(lib.trees.LOGS) - before} logs")
    return r


def run(at=None, radius=32):
    """Fells and replants the giant tree nearest to at (the bot where None) and gives back what it borrowed. Gives fell's
    result()."""
    try:
        r = fell(at if at is not None else pos(), radius)
        if not r["ok"]:
            warn("giant tree:", r["reason"], r["at"], r["missing"])
    finally:
        give_back()
        if borrowed():
            warn("could not give back", borrowed())
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(Pos(int(args[0]), int(args[1]), int(args[2])) if len(args) >= 3 else None,
        int(args[-1]) if len(args) in (1, 4) else 32)
```

## How it works

- [`next_tree`](../reference/modules/lib/trees.md) with `where=is_giant` and `around` finds the base of the tree with a 2x2 trunk nearest to the position, or to the bot.
- [`tree_blocks`](../reference/modules/lib/trees.md) lists the tree's logs, branches too, so [`ensure_tool`](../reference/modules/lib/get.md) knows how many blocks the axe must last: it holds the cheapest axe that does, one the bot carries, one from a chest (borrowed) or one it makes. For a giant tree's few dozen logs, a stone axe it makes beats a wooden one, and an iron axe in a nearby chest beats making one.
- While it gets the axe, the dirt and the saplings, the tree's logs are in `lib.trees.KEEP`, so a bot that must make an axe from nothing takes the logs for it from another tree, not from the one it is about to fell; `finally` empties `KEEP` again.
- A bot fells a tree taller than it reaches by pillaring up on scaffold blocks, so [`get`](../reference/modules/lib/get.md) makes sure it carries a dirt for each block of the tree's height, borrowed from a chest or dug up, and four saplings of the tree's kind where a chest has them. Without, it replants with the saplings that fell, if any.
- [`fell_whole`](../reference/modules/lib/trees.md) fells the tree from the top down, takes back the dirt it stood on, picks up the logs and plants a sapling on each of the four spots of the trunk.
- [`give_back`](../reference/modules/lib/borrow.md) in `finally` returns what was borrowed, the worn axe and the dirt, also when a step failed or the script stopped with an error; what does not fit back the bot keeps, and the job warns of it.
- A step that fails ends the job with a `warn` of its reason, such as `no_tool` or `no_tree`, which the bot's log shows (`/antcraft bot log`).

`GiantTreeGameTest` runs the bundled job on a 2x2 spruce built in a test area: once with a stone axe the bot makes, once with an iron axe from a chest, which goes back worn, and once with an empty bot that takes the logs for its axe from a small oak beside the giant.

## See also

- [Library modules](../reference/index.md#library-modules) and the plan in [The script library](../design/library.md)
- [Errors](../language/errors.md), for `try` and `finally`
