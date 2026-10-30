# Trying an action again on a passing failure, for any script: `import lib.retry`, then
# lib.retry.retry(place, (p, "ladder", "north")).
# A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

# Reasons that pass: a reflex took over (a mob, lava), it was slow, or something
# stood in the way for a moment, such as falling gravel.
AGAIN = ("interrupted", "timeout", "blocked")


def call(action, args):
    """action called with the values of the tuple args as its arguments."""
    # ponytail: the language has no f(*args), so up to 4 arguments; add star calls to the language for more.
    n = len(args)
    assert n <= 4, f"retry takes at most 4 arguments, not {n}"
    if n == 0:
        return action()
    if n == 1:
        return action(args[0])
    if n == 2:
        return action(args[0], args[1])
    if n == 3:
        return action(args[0], args[1], args[2])
    return action(args[0], args[1], args[2], args[3])


def retry(action, args, tries=5, again=None, delay=4):
    """Calls action with args until it succeeds, fails with a reason not in again (AGAIN by default), or it was tried
    tries times; waits delay ticks between tries. Gives the last result."""
    again = AGAIN if again is None else again
    r = call(action, args)
    for _ in range(tries - 1):
        if r or r.reason not in again:
            return r
        wait(delay)
        r = call(action, args)
    return r
