# Building a blueprint end to end, for any script: `from lib.build import build_here`, then
# build_here("antcraft:farm", Pos(10, 64, 10)). A module of the mod's default library (lib/), a kit: it imports the
# resolver and primitives below it (docs/design/library.md, Kits). It gets what the site lacks, builds and gives back
# what it borrowed.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.borrow
from lib.result import result


def materials(id, at, turn=0, mirror=False):
    """What the blueprint id built at at (turned, mirrored) still lacks there: a dict of item to count, empty when it
    stands; None where there is no blueprint id."""
    p = blueprint(id, at, turn, mirror)
    return None if p is None else p["missing"]


def short(left):
    """What of left, item to count, the bot does not carry."""
    out = {}
    for item, n in left.items():
        if count(item) < n:
            out[item] = n - count(item)
    return out


def build_here(id, at, turn=0, mirror=False, radius=32):
    """Gets each item the site lacks with lib.get within radius, in the blueprint's order, then builds what it has, and
    again while each round leaves less missing. Gives result(): ok once it stands; no_blueprint; the reason of the
    build, of the first get that failed (after building what it got), or no_progress, at at, with missing what the bot
    lacks to finish. Gives back what it borrowed during the call (lib.borrow.give_back), also on a failure."""
    since = len(lib.borrow.borrowed())
    try:
        r = rounds(id, at, turn, mirror, radius)
    finally:
        lib.borrow.give_back(since=since)
    if not r["ok"] and r["reason"] != "no_blueprint":
        # After give_back: what went back to its chest the bot lacks again.
        r["missing"] = short(materials(id, at, turn, mirror))
    return r


def rounds(id, at, turn, mirror, radius):
    # Imported here: lib.get loads most of the library, which a script that needs only materials need not wait for.
    from lib.get import get
    before = None
    while True:
        left = materials(id, at, turn, mirror)
        if left is None:
            return result("no_blueprint", at)
        if before is not None and sum(left.values()) >= before:
            return result("no_progress", at)
        before = sum(left.values())
        failed = None
        for item, n in left.items():
            r = get(item, n, radius)
            if not r["ok"] and failed is None:
                failed = r["reason"]
        # Builds what it has also when a get failed: build goes on to the first step it lacks a block for (#585).
        b = build(id, at, turn, mirror)
        if b:
            return result()
        if b.reason != "missing_ingredients":
            return result(b.reason, at)
        if failed is not None:
            return result(failed, at)
