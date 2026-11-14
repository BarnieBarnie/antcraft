# A job: picks a tree kind by itself and starts its wood farm where the bot stands. Run it as
# /antcraft bot run jobs.forester [radius]: how far to look for saplings in chests and for trees, and for what the farm
# needs, in blocks (32 by default). The candidates are the kinds of lib.treefarm.KINDS whose saplings a chest within
# radius holds (lib.stock), then those of a tree within radius (lib.trees.next_tree); it says its choice and why, then
# runs jobs.wood_farm for that kind, which never returns (docs/cookbook/wood-farm.md, A forester). With no candidate it
# warns no_saplings and stops.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import jobs.wood_farm
import lib.stock
import lib.treefarm
import lib.trees


def cost(kind):
    """How hard a cell of kind is to build and farm, lowest first: a 1x1 trunk before a 2x2, no roof before a roof, a
    smaller cell, then a shorter clear."""
    s = lib.treefarm.KINDS[kind]
    return (s["square"], s["roof"], s["size"], s["clear"])


def candidates(radius=32):
    """The kinds of lib.treefarm.KINDS the bot can start a farm of, best first, as (kind, why): those whose saplings a
    chest within radius holds, then those of a tree within radius; each by cost, ties by distance."""
    chests = lib.stock.chests(radius)
    found = []
    for kind in lib.treefarm.KINDS:
        held = [c for c in chests if lib.stock.holds(c, kind)]
        if held:
            d = int(lib.stock.distance(held[0]))
            found.append((0, cost(kind), d, kind, f"saplings in a chest {d} blocks away"))
            continue
        base = lib.trees.next_tree(radius, lambda b, k=kind: lib.trees.sapling(block(b).id) == k)
        if base is not None:
            d = int(lib.stock.distance(base))
            found.append((1, cost(kind), d, kind, f"a tree {d} blocks away"))
    return [(c[3], c[4]) for c in sorted(found)]


def run(radius=32):
    """Starts the farm of the first of candidates(radius) where the bot stands; never returns but on a failed farm
    setup step. Gives None after warning no_saplings where there is no candidate."""
    found = candidates(radius)
    if not found:
        warn("forester: no_saplings within", radius)
        return None
    kind, why = found[0]
    say(f"forester: {kind.split(':')[1]}, {why}")
    return jobs.wood_farm.run(kind, None, None, radius)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(int(args[0]) if args else 32)
