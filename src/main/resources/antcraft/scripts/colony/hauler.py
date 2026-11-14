# A colony hauler: takes "haul" jobs off the colony's job board and brings the
# items to the Colony Core, or to the container the job names. Without jobs it
# waits where it is. Run it on a colony member with `/antcraft bot run colony.hauler`.
#
# A haul job's data is a dict:
#   "item"  a selector: an item id such as "minecraft:cobblestone", a tag such
#           as "#minecraft:logs", or a list of these
#   "n"     how many (optional; all it finds when left out)
#   "from"  the container to take them from (optional; else every chest and
#           barrel in the colony that holds them, nearest first)
#   "to"    the container to bring them to (optional; else the core)
#   "for"   the id of the job it is for (optional): once that job is done, a haul
#           that finds nothing is finished without a warning
# Post one from any member's script:
#   post_job("haul", {"item": "minecraft:oak_log", "n": 16})
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core
import lib.stock


def holders(item):
    """The containers in the colony that hold item, nearest first (lib.stock.where); the core left out."""
    return [p for p in lib.stock.where(item) if p != core()]


def haul(job):
    d = job["data"]
    item = d["item"]
    n = d.get("n")
    sources = [d["from"]] if d.get("from") is not None else holders(item)
    why = "no_item"  # no container holds it
    for p in sources:
        if n is not None and count(item) >= n:
            break
        r = take(p, item, None if n is None else n - count(item))
        if not r:
            why = r.reason
    if has(item):
        to = d.get("to")
        put(core() if to is None else to, item, n)
    elif d.get("for") is None or any([j["id"] == d["for"] for j in jobs()]):
        warn(f"no {item} to haul in the colony: {why}")
    # else the job it was for is done: its poster got the items another way (#557), so there is nothing to say.
    finish_job(job["id"])


core()  # a bot in no colony says so and stops
while True:
    job = take_job("haul")
    if job is None:
        wait(40)
    else:
        haul(job)
