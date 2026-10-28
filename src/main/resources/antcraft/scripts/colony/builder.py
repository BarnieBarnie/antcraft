# A colony builder: takes "build" jobs off the colony's job board, fetches the
# materials from the Colony Core and builds. What the core lacks it asks a
# hauler for with a "haul" job (see colony.hauler), and it waits until the
# materials are there: alone, it waits for a player to fill the core. Run it on
# a colony member with `/antcraft bot run colony.builder`.
#
# A build job's data is a dict:
#   "blueprint"  the blueprint's id, such as "antcraft:cobble_step"
#   "at"         the lowest corner to build at
#   "turn"       quarters turned clockwise (optional, 0)
#   "mirror"     mirrored left to right (optional, False)
# Post one from any member's script:
#   post_job("build", {"blueprint": "antcraft:farm", "at": colony()["core"] + (4, -1, 0)})
# A finished building with a role (a farm, storage) is added to
# colony_memory["buildings"], where colony.farmer finds its farms.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import fetch, store


def gather(materials):
    """Takes from the core what the bot lacks of materials; gives the items still short, with how many."""
    short = {}
    for item, n in materials.items():
        if not fetch(item, n):
            short[item] = n - count(item)
    return short


def ask_haulers(job, short):
    """Posts a haul job for each item short, unless one for this build is on the board already."""
    asked = [h["data"]["item"] for h in jobs("haul") if h["data"].get("for") == job["id"]]
    for item, n in short.items():
        if item not in asked:
            post_job("haul", {"item": item, "n": n, "for": job["id"]})


def build_job(job):
    d = job["data"]
    plan = blueprint(d["blueprint"])
    if plan is None:
        say(f"there is no blueprint {d['blueprint']}")
        finish_job(job["id"])
        return
    while True:
        short = gather(plan["materials"])
        r = build(d["blueprint"], d["at"], d.get("turn", 0), d.get("mirror", False), job=job["id"])
        if r:
            break
        if r.reason != "missing_ingredients":
            warn(f"building {d['blueprint']} stopped: {r.reason}")
            drop_job(job["id"])  # back on the board, to try again later
            wait(200)
            return
        ask_haulers(job, short)
        wait(40)
    for item in plan["materials"]:
        store(item)
    if plan["role"] is not None:
        colony_memory.setdefault("buildings", []).append(d)
    finish_job(job["id"])


while True:
    job = take_job("build")
    if job is None:
        wait(40)
    else:
        build_job(job)
