# A job: builds a blueprint, getting what its site lacks by lib.get first. Run it with
# `/antcraft bot run jobs.build <blueprint> x y z [turn] [radius]`, such as `jobs.build antcraft:cobble_step 10 64 10`.
# It says whether it built it or why not, and gives back what it borrowed on the way, also when it fails
# (docs/cookbook/build.md). Another script runs it with run(id, at, turn, radius); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from lib.build import build_here

def run(id, at, turn=0, radius=32):
    """Builds the blueprint id at at, turned turn, and says whether it built it. Gives build_here's result()."""
    r = build_here(id, at, turn, radius=radius)
    say(f"built {id}" if r["ok"] else f"not built {id}: {r['reason']} {r['missing']}")
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    if len(args) < 4:
        say("usage: jobs.build <blueprint> x y z [turn] [radius]")
        stop()
    run(args[0], Pos(int(args[1]), int(args[2]), int(args[3])), int(args[4]) if len(args) > 4 else 0,
        int(args[5]) if len(args) > 5 else 32)
