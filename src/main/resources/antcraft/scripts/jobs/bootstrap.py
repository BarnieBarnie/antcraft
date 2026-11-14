# bot run jobs.bootstrap [radius]: an empty-handed bot gets lib.bootstrap.KIT from what is within radius (32), then
# gives back what it borrowed, also when it fails (docs/design/library.md, Example jobs). Another script runs it with run(radius); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.bootstrap
from lib.borrow import give_back

def run(radius=32):
    """Gets lib.bootstrap.KIT from what is within radius, says how it went and gives back what it borrowed, also when it
    fails. Gives lib.bootstrap.kit's result()."""
    try:
        r = lib.bootstrap.kit(radius=radius)
        say("kit ready" if r["ok"] else f"no kit: {r['reason']}, missing {r['missing']}")
    finally:
        give_back()
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(int(args[0]) if args else 32)
