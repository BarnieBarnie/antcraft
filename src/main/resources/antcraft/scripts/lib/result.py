# The result every library doer gives, for any script: `from lib.result import result`.
# A module of the mod's default library (lib/), its bottom layer: it imports nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.


def result(reason=None, at=None, missing=None):
    """What a library doer gives: ok, the reason it stopped (as actions give), where, and the items it lacks."""
    return {"ok": reason is None, "reason": reason, "at": at, "missing": missing or {}}
