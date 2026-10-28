# What a bot does when it has no script to run, or its script ended, stopped or
# failed: walk home, then wait there. home() is where the bot was first spawned,
# until a script calls set_home(pos). In another dimension than its home's, the
# bot has no way home and waits where it is.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
while True:
    if pos() != home() and dimension() == home_dimension():
        if not goto(home()):
            # No way home for now: try again in a while.
            wait(100)
    wait(20)
