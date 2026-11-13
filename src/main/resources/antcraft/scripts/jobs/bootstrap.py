# bot run jobs.bootstrap [radius]: an empty-handed bot gets lib.bootstrap.KIT from what is within radius (32), then
# gives back what it borrowed, also when it fails (docs/design/library.md, Example jobs).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.bootstrap
from lib.borrow import give_back

radius = int(ARGS[0]) if ARGS else 32
try:
    r = lib.bootstrap.kit(radius=radius)
    say("kit ready" if r["ok"] else f"no kit: {r['reason']}, missing {r['missing']}")
finally:
    give_back()
