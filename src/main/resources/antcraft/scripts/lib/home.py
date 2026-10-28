# Going home, for any script: `from lib.home import go_home`, or `import lib.home`
# and call lib.home.go_home(). A module of the mod's default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

# The dimensions a nether portal goes between.
NETHER_PORTAL_WORLDS = ("minecraft:overworld", "minecraft:the_nether")


def at_home():
    """Whether the bot stands at its home, in its home's dimension."""
    return pos() == home() and dimension() == home_dimension()


def to_home_dimension():
    """Goes through a nether portal within 32 blocks into the home's dimension; gives whether the bot is there."""
    if dimension() == home_dimension():
        return True
    if dimension() not in NETHER_PORTAL_WORLDS or home_dimension() not in NETHER_PORTAL_WORLDS:
        return False
    portal = find_nearest("nether_portal")
    return portal is not None and bool(travel(portal))


def go_home(tries=3):
    """Walks home, through a nether portal within 32 blocks when the home is in another dimension; gives True once
    there, False when no way home was found in a few tries."""
    for _ in range(tries):
        if at_home():
            return True
        if not to_home_dimension() or not goto(home()):
            wait(20)
    return at_home()
