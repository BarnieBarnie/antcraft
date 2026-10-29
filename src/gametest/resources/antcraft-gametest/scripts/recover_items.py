# A lumberjack: chops the trees around its home and puts the logs in the chest
# by its home. After a death it first fetches what it dropped.
from lib.home import go_home


def recover():
    """Walks back to where the bot last died and picks up what it dropped there."""
    p = last_death()
    if p is None:
        return
    if p.dimension != dimension():
        warn("I died in", p.dimension, "and cannot fetch my things from here")
        return
    r = goto(p, near=2)
    if not r:
        warn("cannot get back to where I died:", r.reason)
        return
    got = pick_up(radius=8)
    say(f"picked up {got.value} items where I died")


# The first run only takes note of the deaths so far.
if "deaths" not in memory:
    memory["deaths"] = deaths()
if deaths() > memory["deaths"]:
    memory["deaths"] = deaths()
    recover()
    go_home()

chest = find_nearest("chest", radius=8)
if chest is None:
    say("put a chest by my home")
    stop()

while True:
    log = find_nearest("#minecraft:logs", radius=24)
    if log is None:
        wait(1200)  # a minute; saplings may have grown by then
        continue
    r = mine(log)
    if not r:
        warn(f"cannot chop {log}: {r.reason}")
        wait(200)
    if count("#minecraft:logs") >= 32 or free_slots() < 2:
        put(chest, "#minecraft:logs")
