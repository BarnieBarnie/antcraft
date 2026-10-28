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


def portal_out():
    """The nether portal to go through: the nearest within 32 blocks, else the nearest the bot went through in this
    dimension (portals()), which may be far; None without either."""
    portal = find_nearest("nether_portal")
    if portal is None:
        for p in portals():
            if p.dimension == dimension() and (portal is None or pos().distance(p) < pos().distance(portal)):
                portal = p
    return portal


def to_home_dimension():
    """Goes through a nether portal (portal_out()) into the home's dimension; gives whether the bot is there."""
    if dimension() == home_dimension():
        return True
    if dimension() not in NETHER_PORTAL_WORLDS or home_dimension() not in NETHER_PORTAL_WORLDS:
        return False
    portal = portal_out()
    return portal is not None and bool(travel(portal))


def go_home(tries=3):
    """Walks home, through a nether portal (portal_out()) when the home is in another dimension; gives True once
    there, False when no way home was found in a few tries."""
    for _ in range(tries):
        if at_home():
            return True
        if not to_home_dimension() or not goto(home()):
            wait(20)
    return at_home()
