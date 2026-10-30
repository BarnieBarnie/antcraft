# Trying an action again on a passing failure, for any script: `import lib.retry`, then
# lib.retry.retry(place, (p, "ladder", "north")).
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

# Reasons that pass: a reflex took over (a mob, lava), it was slow, or something
# stood in the way for a moment, such as falling gravel.
AGAIN = ("interrupted", "timeout", "blocked")


def retry(action, args, tries=5, again=None, delay=4, kwargs=None):
    """Calls action with args, and the dict kwargs as keyword arguments, until it succeeds, fails with a reason not in
    again (AGAIN by default), or it was tried tries times; waits delay ticks between tries. Gives the last result."""
    again = AGAIN if again is None else again
    kwargs = {} if kwargs is None else kwargs
    r = action(*args, **kwargs)
    for _ in range(tries - 1):
        if r or r.reason not in again:
            return r
        wait(delay)
        r = action(*args, **kwargs)
    return r
