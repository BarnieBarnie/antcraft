# A job: puts away what the bot carries and goes home. Run it as /antcraft bot run jobs.store [radius], such as
# jobs.store 16: it gives back what it borrowed and deposits the rest but its tools, weapons, torches and food in the
# chests within radius blocks (32 by default), each item with its kind (lib.storage.deposit), then walks home
# (docs/reference/modules/lib/storage.md).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.storage
from lib.home import go_home

r = lib.storage.deposit(radius=int(ARGS[0]) if len(ARGS) > 0 else 32)
go_home()
if not r["ok"]:
    warn("store:", r["reason"], r["missing"])
