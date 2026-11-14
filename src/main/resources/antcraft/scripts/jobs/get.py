# A job: gets any item from what is around, with the tools to get it, by lib.get's resolver. Run it with
# `/antcraft bot run jobs.get <item> [n] [radius]`, such as `jobs.get iron_pickaxe`: logs, a wooden pickaxe,
# cobblestone, a stone pickaxe, iron ore, a furnace and the iron pickaxe. It says what it got or why not, and gives back
# what it borrowed on the way, also when it fails; the n it asked for it keeps (docs/cookbook/get.md). Another script
# runs it with run(item, n, radius); imported, this runs nothing.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from lib.get import get, key
from lib.borrow import give_back, keep

def run(item, n=1, radius=32):
    """Gets n of item, keeps them, gives back what it borrowed on the way and says how it went. Gives get's result()."""
    try:
        r = get(item, n, radius)
    finally:
        # The n asked for are the job's: kept, so give_back does not take them back.
        keep(key(item), n)
        give_back()
    say(f"got {n} {item}" if r["ok"] else f"no {item}: {r['reason']} {r['missing']}")
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    if not args:
        say("usage: jobs.get <item> [n] [radius]")
        stop()
    run(args[0], int(args[1]) if len(args) > 1 else 1, int(args[2]) if len(args) > 2 else 32)
