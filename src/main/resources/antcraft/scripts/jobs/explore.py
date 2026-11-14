# A job: explores a square spiral around the bot until it sees what, a biome or structure, then goes home and reports
# what it found. Run it as /antcraft bot run jobs.explore [what] [rings] [step], such as jobs.explore village: what an
# id, a biome tag such as #minecraft:is_forest or part of an id (lib.explore.matches), all (the default) to walk the
# whole spiral; rings 4 and step 32, so it looks up to 128 blocks out (docs/cookbook/explore.md).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.explore
from lib.home import go_home

what = ARGS[0] if len(ARGS) > 0 and ARGS[0] != "all" else None
rings = int(ARGS[1]) if len(ARGS) > 1 else 4
step = int(ARGS[2]) if len(ARGS) > 2 else 32

until = None if what is None else lambda s: len(lib.explore.seen(what)) > 0
r = lib.explore.explore(rings, step, until)
go_home()
lib.explore.report()
if not r["ok"]:
    warn("explore:", what, r["reason"])
