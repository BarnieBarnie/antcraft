# A colony builder: takes "build" jobs off the colony's job board, fetches the
# materials from the Colony Core and builds. What the core lacks it gets itself
# with lib.get (cobblestone, planks, logs, dirt: gathered or crafted within the
# colony, chests in it borrowed from); what get fails on it asks a hauler for
# with a "haul" job (see colony.hauler), and it waits until the materials are
# there: alone, it waits for a player to fill the core. Run it on a colony
# member with `/antcraft bot run colony.builder`.
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
from colony.common import core, store
from lib.borrow import give_back
from lib.stock import take_up_to


def get_or_haul(job, short, need):
    """Gets each item short with lib.get, up to need[item], and posts a haul job for what get fails on, unless one for
    this build is on the board already: while it is, get does not try that item again, so one it cannot get costs one
    try and does not hold up the others. ponytail: in the blueprint's order, one item at a time."""
    # Imported here: lib.get loads most of the library, which a bot in no colony need not wait for.
    from lib.get import get
    asked = [h["data"]["item"] for h in jobs("haul") if h["data"].get("for") == job["id"]]
    for item in short:
        if item not in asked and not get(item, need[item])["ok"]:
            post_job("haul", {"item": item, "n": need[item] - count(item), "for": job["id"]})


def build_job(job):
    d = job["data"]
    plan = blueprint(d["blueprint"])
    if plan is None:
        say(f"there is no blueprint {d['blueprint']}")
        finish_job(job["id"])
        return
    while True:
        short = [item for item, n in plan["materials"].items() if not take_up_to(item, n, chests=[core()])["ok"]]
        r = build(d["blueprint"], d["at"], d.get("turn", 0), d.get("mirror", False), job=job["id"])
        if r:
            break
        if r.reason != "missing_ingredients":
            warn(f"building {d['blueprint']} stopped: {r.reason}")
            give_back()
            drop_job(job["id"])  # back on the board, to try again later
            wait(200)
            return
        get_or_haul(job, short, plan["materials"])
        wait(40)
    # What get borrowed and the build did not use (a tool, spare planks) goes back to its chest first.
    give_back()
    for item in plan["materials"]:
        store(item)
    if plan["role"] is not None:
        colony_memory.setdefault("buildings", []).append(d)
    finish_job(job["id"])


core()  # a bot in no colony says so and stops
while True:
    job = take_job("build")
    if job is None:
        wait(40)
    else:
        build_job(job)
