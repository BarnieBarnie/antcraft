# A job: builds a blueprint, getting what its site lacks by lib.get first. Run it with
# `/antcraft bot run jobs.build <blueprint> x y z [turn] [radius]`, such as `jobs.build antcraft:cobble_step 10 64 10`.
# It says whether it built it or why not, and gives back what it borrowed on the way, also when it fails
# (docs/cookbook/build.md).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from lib.build import build_here

if len(ARGS) < 4:
    say("usage: jobs.build <blueprint> x y z [turn] [radius]")
    stop()
id = ARGS[0]
at = Pos(int(ARGS[1]), int(ARGS[2]), int(ARGS[3]))
turn = int(ARGS[4]) if len(ARGS) > 4 else 0
radius = int(ARGS[5]) if len(ARGS) > 5 else 32
r = build_here(id, at, turn, radius=radius)
say(f"built {id}" if r["ok"] else f"not built {id}: {r['reason']} {r['missing']}")
