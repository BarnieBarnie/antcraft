# A job: puts away what the bot carries and goes home. Run it as /antcraft bot run jobs.store [radius], such as
# jobs.store 16: it gives back what it borrowed and deposits the rest but its tools, weapons, torches and food in the
# chests within radius blocks (32 by default), each item with its kind (lib.storage.deposit), then walks home
# (docs/reference/modules/lib/storage.md). Another script runs it with run(radius); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.storage
from lib.home import go_home

def run(radius=32):
    """Deposits what the bot carries in the chests within radius and goes home. Gives lib.storage.deposit's result()."""
    r = lib.storage.deposit(radius=radius)
    go_home()
    if not r["ok"]:
        warn("store:", r["reason"], r["missing"])
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(int(args[0]) if len(args) > 0 else 32)
