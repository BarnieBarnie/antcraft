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
    """Gets what the site lacks with lib.get within radius, for the steps up to the first item it cannot get (fetch),
    then builds what it has, and again while each round leaves less missing. Gives result(): ok once it stands; no_blueprint; the reason of the
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
        p = blueprint(id, at, turn, mirror)
        if p is None:
            return result("no_blueprint", at)
        if before is not None and len(p["steps"]) >= before:
            return result("no_progress", at)
        before = len(p["steps"])
        failed = fetch(p["steps"], radius, get)
        # Builds what it has also when a get failed: build goes on to the first step it lacks a block for (#585).
        b = build(id, at, turn, mirror)
        if b:
            return result()
        if b.reason != "missing_ingredients":
            return result(b.reason, at)
        if failed is not None:
            return result(failed, at)


def fetch(steps, radius, get):
    """Gets with get the items of steps, one for each step still to build in build order (blueprint's steps), as far as
    build gets with them: build stops at the first step it lacks a block for, so the steps after the first item it
    cannot get wait for a later call (#599). It gets each item in the order of its first step, as many as the steps
    before the next item's first step take, then the rest of what the steps before the first item it could not get
    take, so an item placed both before and after that one is got only for the steps before (#620). Gives the reason
    of the first get that failed, or None.
    ponytail: an item placed on both sides of another's first step is got in two trips; fine while chests are near."""
    seen = {}
    end = len(steps)
    last = None
    for i in range(len(steps) + 1):
        if i < len(steps) and steps[i] in seen:
            seen[steps[i]] = seen[steps[i]] + 1
            continue
        if last is not None:
            r = get(last, seen[last], radius)
            if not r["ok"]:
                end = first
                break
        if i < len(steps):
            last = steps[i]
            first = i
            seen[last] = 1
    failed = None if end == len(steps) else r["reason"]
    need = {}
    for item in steps[:end]:
        need[item] = need.get(item, 0) + 1
    for item, n in need.items():
        r = get(item, n, radius)
        if not r["ok"]:
            return failed or r["reason"]
    return failed
