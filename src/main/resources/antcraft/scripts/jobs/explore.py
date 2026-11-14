# A job: explores a square spiral around the bot until it sees what, a biome or structure, then goes home and reports
# what it found. Run it as /antcraft bot run jobs.explore [what] [rings] [step], such as jobs.explore village: what an
# id, a biome tag such as #minecraft:is_forest or part of an id (lib.explore.matches), all (the default) to walk the
# whole spiral; rings 4 and step 32, so it looks up to 128 blocks out (docs/cookbook/explore.md). Another script runs
# it with run(what, rings, step); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.explore
from lib.home import go_home

def run(what=None, rings=4, step=32):
    """Explores until the bot sees what (None: the whole spiral), goes home and reports. Gives lib.explore.explore's
    result()."""
    until = None if what is None else lambda s: len(lib.explore.seen(what)) > 0
    r = lib.explore.explore(rings, step, until)
    go_home()
    lib.explore.report()
    if not r["ok"]:
        warn("explore:", what, r["reason"])
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(args[0] if len(args) > 0 and args[0] != "all" else None, int(args[1]) if len(args) > 1 else 4,
        int(args[2]) if len(args) > 2 else 32)
