# A colony hauler: takes "haul" jobs off the colony's job board and brings the
# items to the Colony Core, or to the container the job names. Without jobs it
# waits at home. Run it on a colony member with `/antcraft bot run colony.hauler`.
#
# A haul job's data is a dict:
#   "item"  the item id, such as "minecraft:cobblestone"
#   "n"     how many (optional; all it finds when left out)
#   "from"  the container to take them from (optional; else every chest and
#           barrel in the colony that holds them, nearest first)
#   "to"    the container to bring them to (optional; else the core)
# Post one from any member's script:
#   post_job("haul", {"item": "minecraft:oak_log", "n": 16})
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core, in_colony
from lib.home import go_home

CONTAINERS = ["chest", "trapped_chest", "barrel"]


def holders(item):
    """The containers in the colony that hold item, nearest first; the core left out."""
    found = []
    for p in scan(CONTAINERS, radius=32):
        if p != core() and in_colony(p) and any([s.item == item for s in container(p) or []]):
            found.append(p)
    return found


def haul(job):
    d = job["data"]
    item = d["item"]
    n = d.get("n")
    sources = [d["from"]] if d.get("from") is not None else holders(item)
    for p in sources:
        if n is not None and count(item) >= n:
            break
        take(p, item, None if n is None else n - count(item))
    if has(item):
        to = d.get("to")
        put(core() if to is None else to, item, n)
    else:
        warn(f"no {item} to haul in the colony")
    finish_job(job["id"])


while True:
    job = take_job("haul")
    if job is None:
        go_home()  # out of the way: a bot standing where a block goes blocks a build
        wait(40)
    else:
        haul(job)
