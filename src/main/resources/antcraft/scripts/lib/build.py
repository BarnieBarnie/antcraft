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


def build_here(id, at, turn=0, mirror=False, radius=32, each=None):
    """Gets what the site lacks with lib.get within radius, for the steps up to the first item it cannot get (fetch),
    then builds what it has, and again while each round leaves less missing. Gives result(): ok once it stands; no_blueprint; the reason of the
    build, of the first get that failed (after building what it got), or no_progress, at at, with missing what the bot
    lacks to finish. Gives back what it borrowed during the call (lib.borrow.give_back), also on a failure. each, where given,
    is called with no arguments before each get and each build, such as lib.food.upkeep to keep the bot fed on a long build
    (#757); lib.build imports nothing above it for that."""
    since = len(lib.borrow.borrowed())
    try:
        r = rounds(id, at, turn, mirror, radius, each)
    finally:
        lib.borrow.give_back(since=since)
    if not r["ok"] and r["reason"] != "no_blueprint":
        # After give_back: what went back to its chest the bot lacks again.
        r["missing"] = short(materials(id, at, turn, mirror))
    return r


def rounds(id, at, turn, mirror, radius, each=None):
    # Imported here: lib.get loads most of the library, which a script that needs only materials need not wait for.
    from lib.get import get
    each = each if each is not None else (lambda: None)
    before = None
    while True:
        p = blueprint(id, at, turn, mirror)
        if p is None:
            return result("no_blueprint", at)
        if before is not None and len(p["steps"]) >= before:
            return result("no_progress", at)
        before = len(p["steps"])
        failed = fetch(p["steps"], radius, get, each)
        # Builds what it has also when a get failed: build goes on to the first step it lacks a block for (#585).
        each()
        b = build(id, at, turn, mirror)
        if b:
            return result()
        if b.reason != "missing_ingredients":
            return result(b.reason, at)
        if failed is not None:
            return result(failed, at)


def fetch(steps, radius, get, each=lambda: None):
    """Gets with get the items of steps, one for each step still to build in build order (blueprint's steps), as far as
    build gets with them: build stops at the first step it lacks a block for, so the steps after the first item it
    cannot get wait for a later call (#599). It gets each item in the order of its first step, as many as the steps
    before the next item's first step take, and once that is got, tops the items before it up to what those steps
    take, in the same trip (#626). So an item placed both before and after one it could not get is got only for the
    steps before (#620). Calls each before each get. Gives the reason of the first get that failed, or None."""
    need = {}
    got = {}
    last = None
    for i in range(len(steps) + 1):
        if i < len(steps) and steps[i] in need:
            need[steps[i]] = need[steps[i]] + 1
            continue
        if last is not None:
            for item in [last] + [k for k in need if k != last]:
                if need[item] > got.get(item, 0):
                    each()
                    r = get(item, need[item], radius)
                    if not r["ok"]:
                        return r["reason"]
                    got[item] = need[item]
        if i < len(steps):
            last = steps[i]
            need[last] = 1
    return None
