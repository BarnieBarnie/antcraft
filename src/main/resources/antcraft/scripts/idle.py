# What a bot does when it has no script to run, or its script ended, stopped or
# failed: walk home, then wait there. home() is where the bot was first spawned,
# until a script calls set_home(pos). In another dimension than its home's, the
# bot goes through a nether portal within 32 blocks, if there is one, and waits
# where it is otherwise (go_home in lib/home.py).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from lib.home import go_home

while True:
    if not go_home(tries=1):
        # No way home for now: try again in a while.
        wait(100)
    wait(20)
