# Going home, for any script: `from lib.home import go_home`, or `import lib.home`
# and call lib.home.go_home(). A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.


def at_home():
    """Whether the bot stands at its home."""
    return pos() == home()


def go_home(tries=3):
    """Walks home; gives True once there, False when no way home was found in a few tries."""
    for _ in range(tries):
        if at_home():
            return True
        if not goto(home()):
            wait(20)
    return at_home()
