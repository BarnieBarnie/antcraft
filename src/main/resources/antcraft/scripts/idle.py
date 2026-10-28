# What a bot does when it has no script to run, or its script ended, stopped or
# failed: walk home, then wait there. home() is where the bot was first spawned,
# until a script calls set_home(pos). In another dimension than its home's, the
# bot goes through a nether portal within 32 blocks, or else the nearest one it
# went through before in this dimension, and waits where it is without either.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
#
# ponytail: a copy of go_home in lib/home.py, kept here so a changed or broken
# lib/home.py does not change or stop every idle bot (#352).

# The dimensions a nether portal goes between.
NETHER_PORTAL_WORLDS = ("minecraft:overworld", "minecraft:the_nether")


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


while True:
    at_home = pos() == home() and dimension() == home_dimension()
    if not at_home and (not to_home_dimension() or not goto(home())):
        # No way home for now: try again in a while.
        wait(120)
    wait(20)
