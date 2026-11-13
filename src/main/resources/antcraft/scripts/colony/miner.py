# A colony miner: the colony mines through one shared ladder shaft beside its
# Colony Core. The first miner claims the site with a "shaft_site" job (others
# wait while it is taken), picks it, posts a "shaft" job and digs it
# (lib.shaft); then a "mine" job per level goes on the board, and each miner
# takes one, mines a round of that level's branches (lib.mine), brings the ore
# from the landing chest to the core and posts the level again, so the miners
# take turns, the level mined least first. Ladders, a wooden trapdoor for the
# hatch (of the planks or logs the colony has, else oak), torches, chests and
# pickaxes come from the core; what the core lacks the miner gets with lib.get,
# from the colony's chests, trees, stone and ore, and gives back what it
# borrowed once each job is done. The shaft takes a stone pickaxe; each level a
# pickaxe of the tier its ores need (lib.mine.tier_at: iron below -8, for gold,
# redstone and diamonds), as a too-low one leaves their veins in place. Run it
# on a colony member with `/antcraft bot run colony.miner`.
#
# The shaft is kept in colony_memory["mineshaft"]: top and levels once picked.
# Set it before the first miner runs to choose the levels (heights), the size
# of the halls and, if lib.mine.tier_at is not right for a level, its tier:
# {"levels": [16, -16], "branches": 4, "length": 16, "tiers": {16: "iron"}}.
# A mine job's data is a dict: "level" (a height of the shaft) and "round" (the
# round of branches to mine, optional, 0). Post one from any member's script:
#   post_job("mine", {"level": 16})
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.
from colony.common import core, store

core()  # a bot in no colony says so and stops, before the imports below spend its player's instruction budget
import lib.get
import lib.mine
import lib.shaft
import lib.tools
from lib.borrow import give_back
import lib.stock

# The heights to mine at unless colony_memory["mineshaft"] says otherwise: iron, then diamonds.
LEVELS = [16, -16, -54]
ORES = lib.mine.ORES
PICKAXES = "#minecraft:pickaxes"
# The pickaxe tier the shaft takes: any digs it, this one the ore it passes.
TIER = lib.tools.tier_for("minecraft:iron_ore")


def claim():
    """The colony's "shaft_site" job once this bot holds the oldest, which lets it pick the site; None while another
    miner holds it. Ids count up, so of two miners that post one at once only the one holding the older picks."""
    if not jobs("shaft_site"):
        post_job("shaft_site")
    j = take_job("shaft_site")
    if j is not None and jobs("shaft_site")[0]["id"] != j["id"]:
        finish_job(j["id"])
        j = None
    return j


def site():
    """The colony's shaft, picked beside the core by the miner holding the "shaft_site" job (others wait for it), also
    kept in this bot's memory["shaft"] where lib.shaft and lib.mine read it; None where none can be dug."""
    s = colony_memory.setdefault("mineshaft", {})
    while "top" not in s:
        j = claim()
        if j is None:
            wait(20)
            continue
        # Checked again: a miner that held the claim before stores the site before it gives the claim up.
        if "top" not in s:
            m = mineshaft(s.get("levels", LEVELS), near=core())
            if m is None:
                finish_job(j["id"])
                return None
            s["top"] = m["top"]
            s["levels"] = m["levels"]
            post_job("shaft")
        finish_job(j["id"])
    kept = {}
    for k, v in s.items():
        kept[k] = v
    memory["shaft"] = kept
    return s


def hatch():
    """The wooden trapdoor lib.get makes for the hatch: that of the first planks or logs the bot carries or the core
    holds, so of wood the colony has; oak where it has none."""
    for s in inventory() + (container(core()) or []):
        if matches(s, "#minecraft:planks") or matches(s, "#minecraft:logs"):
            wood = s.item.replace("stripped_", "")
            for end in ["_planks", "_log", "_wood", "_stem", "_hyphae", "_block"]:
                wood = wood.replace(end, "")
            if matches(wood + "_trapdoor", lib.shaft.HATCH[0]):
                return wood + "_trapdoor"
    return "minecraft:oak_trapdoor"


def take_more(more):
    """Takes from the core what the bot lacks of more (item to how many more than it carries); gives what is still short."""
    short = {}
    for item, n in more.items():
        r = lib.stock.take_up_to(item, count(item) + n, chests=[core()])
        if not r["ok"]:
            short[item] = r["missing"][item]
    return short


def supply(more, uses, tier=TIER):
    """Makes the bot hold a pickaxe of at least tier that lasts uses more blocks (lib.get.ensure_tool: carried, from a
    chest, else made) and carry what it lacks of more (item to how many more than it carries): from the core, the rest
    with lib.get.get, the hatch's trapdoor of wood the colony has. Gives result(): ok, or the first that failed."""
    short = take_more(more)
    short.pop(PICKAXES, None)
    r = lib.get.ensure_tool("pickaxe", uses, tier)
    for item, n in short.items():
        if not r["ok"]:
            break
        k = hatch() if item == lib.shaft.HATCH[0] else item
        r = lib.get.get(k, count(k) + n)
    return r


def dig(job):
    """Digs the shared shaft; once dug, posts a mine job for each level."""
    m = lib.shaft.shaft()
    # The blocks the shaft breaks: its column, and at each level the landing, the cell over it and the chest's.
    uses = len(lib.shaft.column(m)) + 3 * len(m["levels"])
    r = supply(lib.shaft.missing(m), uses)
    if r["ok"]:
        r = lib.shaft.dig_shaft(m["levels"])
    if r["ok"]:
        finish_job(job["id"])
        for y in m["levels"]:
            post_job("mine", {"level": y})
        return
    if r["reason"] == "missing_ingredients" and supply(r["missing"], uses)["ok"]:
        return  # it got them: dig on at once
    warn(f"the shaft stopped at {r['at']}: {r['reason']} {r['missing']}")
    wait(200)  # the job stays this bot's: it tries again once the colony has more


def mine(job):
    """Mines the next round of the job's level and brings the ore to the core; posts the level again after it."""
    y = job["data"]["level"]
    r0 = job["data"].get("round", 0)
    m = lib.mine.site(r0)
    if y not in m["level"]:
        warn(f"the shaft has no level at {y}")
        finish_job(job["id"])
        return
    here = m["level"][y]
    # The blocks the round breaks: both cells of the hall, its branches and a few ore. ponytail: the veins are a guess.
    uses = 2 * len(here["hall"]) + sum([len(cells) for cells in here["branches"]]) + 8
    # The tier its ores need: colony_memory["mineshaft"]["tiers"] of the level, else lib.mine.tier_at.
    tier = colony_memory["mineshaft"].get("tiers", {}).get(y) or lib.mine.tier_at(y)
    r = supply({"minecraft:torch": max(len(here["torches"]) - count("minecraft:torch"), 0)}, uses, tier)
    # Short of torches it mines on: mine_level lights what it can.
    if not has(PICKAXES):
        warn(f"mining level {y} waits for a pickaxe: {r['reason']} {r['missing']}")
        wait(200)  # the job stays this bot's: it tries again once the colony has more
        return
    memory["mine"] = {y: r0}
    lib.shaft.to_level(y)
    r = lib.mine.mine_level(y)
    take(here["chest"], ORES)
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
        continue
    try:
        if job["kind"] == "shaft":
            dig(job)
        else:
            mine(job)
    finally:
        give_back()  # what lib.get borrowed from chests in the colony; what it takes from the core is the colony's
