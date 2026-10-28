# A colony miner: the colony mines through one shared ladder shaft beside its
# Colony Core. The first miner picks the site, posts a "shaft" job and digs it
# (lib.shaft); then a "mine" job per level goes on the board, and each miner
# takes one, mines a round of that level's branches (lib.mine), brings the ore
# from the landing chest to the core and posts the level again, so the miners
# take turns, the level mined least first. Ladders, a wooden trapdoor for the
# hatch, torches, chests and pickaxes come from the core. Run it on a colony
# member with `/antcraft bot run colony.miner`.
#
# The shaft is kept in colony_memory["mineshaft"]: top and levels once picked.
# Set it before the first miner runs to choose the levels (heights) and the size
# of the halls: {"levels": [16, -16], "branches": 4, "length": 16}.
# A mine job's data is a dict: "level" (a height of the shaft) and "round" (the
# round of branches to mine, optional, 0). Post one from any member's script:
#   post_job("mine", {"level": 16})
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
import lib.mine
import lib.shaft
from colony.common import core, fetch, store

# The heights to mine at unless colony_memory["mineshaft"] says otherwise: iron, then diamonds.
LEVELS = [16, -16, -54]
ORES = lib.mine.ORES


def site():
    """The colony's shaft, picked beside the core by the first miner, also kept in this bot's memory["shaft"] where
    lib.shaft and lib.mine read it; None where none can be dug."""
    c = core()
    s = colony_memory.setdefault("mineshaft", {})
    if "top" not in s:
        # ponytail: picking and storing is no atomic step; two miners started on one tick could each pick, the last kept
        m = mineshaft(s.get("levels", LEVELS), near=c)
        if m is None:
            return None
        s["top"] = m["top"]
        s["levels"] = m["levels"]
        post_job("shaft")
    kept = {}
    for k, v in s.items():
        kept[k] = v
    memory["shaft"] = kept
    return s


def take_all(need):
    """Takes from the core what the bot lacks of need (item to how many more); gives what is still short."""
    short = {}
    for item, n in need.items():
        if not fetch(item, count(item) + n):
            short[item] = n
    return short


def dig(job):
    """Digs the shared shaft; once dug, posts a mine job for each level."""
    m = lib.shaft.shaft()
    take_all(lib.shaft.missing(m))
    r = lib.shaft.dig_shaft(m["levels"])
    if r["ok"]:
        finish_job(job["id"])
        for y in m["levels"]:
            post_job("mine", {"level": y})
        return
    if r["reason"] == "missing_ingredients" and not take_all(r["missing"]):
        return  # the core had it: dig on at once
    warn(f"the shaft stopped at {r['at']}: {r['reason']} {r['missing']}")
    wait(200)  # the job stays this bot's: it tries again once the core has more


def mine(job):
    """Mines the next round of the job's level and brings the ore to the core; posts the level again after it."""
    y = job["data"]["level"]
    r0 = job["data"].get("round", 0)
    m = lib.mine.site(r0)
    if y not in m["level"]:
        warn(f"the shaft has no level at {y}")
        finish_job(job["id"])
        return
    fetch("#minecraft:pickaxes", 1)
    fetch("minecraft:torch", len(m["level"][y]["torches"]))
    memory["mine"] = {y: r0}
    lib.shaft.to_level(y)
    r = lib.mine.mine_level(y)
    take(m["level"][y]["chest"], ORES)
    lib.shaft.to_surface()
    store(ORES)
    finish_job(job["id"])
    post_job("mine", {"level": y, "round": memory["mine"][y]})
    if not r["ok"]:
        warn(f"mining level {y} stopped at {r['at']}: {r['reason']} {r['missing']}")
        wait(200)


while True:
    if site() is None:
        warn("there is no spot near the core to dig a shaft")
        wait(1200)
        continue
    job = take_job("shaft") or take_job("mine")
    if job is None:
        wait(40)
    elif job["kind"] == "shaft":
        dig(job)
    else:
        mine(job)
