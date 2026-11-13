# A job: gets any item from what is around, with the tools to get it, by lib.get's resolver. Run it with
# `/antcraft bot run jobs.get <item> [n] [radius]`, such as `jobs.get iron_pickaxe`: logs, a wooden pickaxe,
# cobblestone, a stone pickaxe, iron ore, a furnace and the iron pickaxe. It says what it got or why not, and gives back
# what it borrowed on the way, also when it fails; the item it asked for it keeps (docs/cookbook/get.md).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from lib.get import get, key
from lib.borrow import borrowed, give_back, keep

if not ARGS:
    say("usage: jobs.get <item> [n] [radius]")
    stop()
item = ARGS[0]
n = int(ARGS[1]) if len(ARGS) > 1 else 1
radius = int(ARGS[2]) if len(ARGS) > 2 else 32
try:
    r = get(item, n, radius)
finally:
    # The item asked for is the job's: kept, so give_back does not take it back.
    for b in borrowed():
        if key(b["item"]) == key(item):
            keep(b["item"])
    give_back()
say(f"got {n} {item}" if r["ok"] else f"no {item}: {r['reason']} {r['missing']}")
