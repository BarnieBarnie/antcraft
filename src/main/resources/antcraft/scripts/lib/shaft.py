# Digging a ladder shaft, for any script: `import lib.shaft`, then
# lib.shaft.dig_shaft([40, 20]), lib.shaft.to_level(20), lib.shaft.to_surface().
# A module of the mod's default library (lib/).
#
# The shaft is the one mineshaft() picks: a column of ladders under its top, a
# chamber at each level with a torch and a chest. Which shaft is kept in
# memory["shaft"]; how far it is dug is read from the world, so a bot that is
# reloaded, restarted or given more ladders goes on where it stopped.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

LADDER = "minecraft:ladder"
TORCHES = ("minecraft:torch", "minecraft:wall_torch")
CHEST = "minecraft:chest"
# What it walls fluid, air and falling blocks off with: what digging stone gives.
FILL = ["cobblestone", "cobbled_deepslate", "dirt", "stone", "deepslate", "andesite", "diorite", "granite", "tuff", "netherrack"]
# ponytail: the common falling blocks, not concrete powder or anvils.
FALLING = ("minecraft:gravel", "minecraft:suspicious_gravel", "minecraft:sand", "minecraft:red_sand", "minecraft:suspicious_sand")
# Reasons to try a step again: a reflex took over (a mob, lava), it was slow, or
# something passed through the block, such as falling gravel.
AGAIN = ("interrupted", "timeout", "blocked")
# How often a block is tried, as gravel may keep falling in.
TRIES = 8


def result(reason=None, at=None, missing=None):
    """What dig_shaft gives: ok, the reason it stopped (as actions give), where, and the items it lacks."""
    return {"ok": reason is None, "reason": reason, "at": at, "missing": missing or {}}


def shaft(levels=None):
    """The shaft in memory["shaft"], or else a new one mineshaft(levels) finds beside the bot, kept there; None where
    none can be dug. Forget it with memory.pop("shaft") to dig another."""
    kept = memory.get("shaft")
    if kept is not None:
        return mineshaft_at(kept["top"], kept["levels"])
    if levels is None:
        return None
    m = mineshaft(levels)
    if m is not None:
        memory["shaft"] = {"top": m["top"], "levels": m["levels"]}
    return m


def column(m):
    """The cells of the column, from the hatch down: a ladder goes in each, the hatch's too, to climb out by."""
    return [m["hatch"]] + [ladder[0] for ladder in m["ladders"]]


def is_block(p, ids):
    b = block(p)
    return b is not None and b.id in ids


def missing(m):
    """The ladders, torches and chests the rest of the shaft takes that the bot does not carry, and a pickaxe."""
    need = {}
    wants = {
        LADDER: len([c for c in column(m) if not is_block(c, (LADDER,))]),
        "minecraft:torch": len([y for y in m["levels"] if not is_block(m["level"][y]["landing"], TORCHES)]),
        CHEST: len([y for y in m["levels"] if not is_block(m["level"][y]["chest"], (CHEST,))]),
    }
    for item, n in wants.items():
        if count(item) < n:
            need[item] = n - count(item)
    if any(wants.values()) and not has("#minecraft:pickaxes"):
        need["#minecraft:pickaxes"] = 1
    return need


def seal(p, stand):
    """Makes p a solid block, standing at stand: places a FILL block in air or a fluid, and digs a falling block out
    first."""
    for _ in range(TRIES):
        b = block(p)
        if b is None:
            return result("not_loaded", p)
        if pos() != stand and (b.id in FALLING or b.is_air or b.is_fluid):
            goto(stand)  # rather than a way the action would dig to see p
        if b.id in FALLING:
            r = mine(p)
            wait(4)  # what was on it falls in
        elif b.is_air or b.is_fluid:
            if not has(FILL):
                return result("missing_ingredients", p, {"minecraft:cobblestone": 1})
            r = place(p, FILL)
            if not r:
                wait(4)
        else:
            return None
        if not r and r.reason not in AGAIN:
            return result(r.reason, p)
    return result("timeout", p)


def dig(p, opened, stand, fall=False):
    """Digs p out standing at stand, then makes every block round it that is not in opened solid, so no fluid comes in
    and nothing falls in, and digs again what fell in meanwhile. With fall, p is the block under the bot, which falls
    or slides into it and walls it off from inside. None once done, else a result."""
    inside = p if fall else stand
    for _ in range(TRIES):
        b = block(p)
        if b is None:
            return result("not_loaded", p)
        if b.is_fluid and pos() != p:
            failure = seal(p, stand)  # a fluid is not mined: fill it, then dig the block
            if failure:
                return failure
            continue
        if not b.is_air and not b.is_fluid and b.id != LADDER and b.id not in TORCHES:
            if pos() != stand:
                goto(stand)  # ponytail: a failed goto shows in the action after it
            r = mine(p)
            if not r and r.reason not in AGAIN:
                return result(r.reason, p)
            wait(4)  # what was on it falls in
            for _ in range(30 if fall else 0):
                if pos() == p:
                    break
                wait(1)
            continue
        for side in (p.north(), p.south(), p.east(), p.west(), p.down(), p.up()):
            if side not in opened:
                failure = seal(side, inside)
                if failure:
                    return failure
        b = block(p)
        if b.is_air or b.id == LADDER or b.id in TORCHES:
            return None
        if b.is_fluid:
            wait(10)  # what flowed in drains once its source is walled off
    return result("timeout", p)


def put_block(p, item, ids, facing=None):
    """Places item at p until a block of ids stands there."""
    for _ in range(TRIES):
        if is_block(p, ids):
            return None
        r = place(p, item, facing) if facing is not None else place(p, item)
        if not r and r.reason not in AGAIN:
            return result(r.reason, p)
        if not r:
            wait(4)
    return result("timeout", p)


def ladders(cells, lowest):
    """Puts a ladder into each cell of the column at or above the height lowest that has none. A bot places no block
    where it stands, so the ladders go in two blocks above its feet as it digs down, and the last two from a landing."""
    for p in cells:
        if p.y >= lowest and not is_block(p, (LADDER,)):
            failure = put_block(p, "ladder", (LADDER,), "north")
            if failure:
                return failure
    return None


def put_chest(p, stand):
    """Digs p out and puts the landing chest there, facing the landing south of it. What flows or falls into p is shut
    in by the chest, so the blocks round it are left as they are. A fluid source in p is filled first: a chest in it
    would hold the water and let it flow out."""
    for _ in range(TRIES):
        b = block(p)
        if b is None:
            return result("not_loaded", p)
        if b.id == CHEST:
            return None
        if b.is_fluid and b.state.get("level", 0) == 0:
            failure = seal(p, stand)
            if failure:
                return failure
            continue
        if b.is_air or b.is_fluid:
            r = place(p, "chest", "south")
        else:
            if pos() != stand:
                goto(stand)
            r = mine(p)
        if not r and r.reason not in AGAIN:
            return result(r.reason, p)
        wait(4)  # what was on it falls in
    return result("timeout", p)


def dig_landing(level, cell, opened):
    """Digs the chamber of a level from the column cell cell, with its chest, and its torch on the floor once what
    flowed in has drained."""
    landing = level["landing"]
    for p in (landing.up(), landing):
        failure = dig(p, opened, cell)
        if failure:
            return failure
    failure = put_chest(level["chest"], cell)
    if failure:
        return failure
    for _ in range(TRIES):
        b = block(landing)
        if b is None or not b.is_fluid:
            break
        wait(10)
    return put_block(landing, "torch", TORCHES)


def dig_shaft(levels):
    """Digs the ladder shaft down to the heights levels (see mineshaft), top down: each block of the column, a ladder in
    each, and at each level its chamber with a torch and a chest. Walls water, lava and air off and digs out what falls
    in. Ends in the chamber of the deepest level. Gives result(): ok, or the reason it stopped (no_site where no shaft
    can be dug, missing_ingredients with missing, the items it lacks, before it digs at all, or an action's reason with
    at, where)."""
    m = shaft(levels)
    if m is None:
        return result("no_site")
    need = missing(m)
    if need:
        return result("missing_ingredients", None, need)
    cells = column(m)
    opened = cells + [m["top"]]
    for y in m["levels"]:
        level = m["level"][y]
        opened = opened + [level["landing"], level["landing"].up(), level["chest"]]
    above = m["top"]
    for p in cells:
        if not is_block(p, (LADDER,)):
            # The bot stands on p, mines it and falls in.
            failure = dig(p, opened, above, True)
            if failure:
                return failure
            if pos().y > p.y:
                failure = put_block(p, "ladder", (LADDER,), "north")  # it stood on the rim of the hole: climb in by it
            else:
                failure = ladders(cells, pos().y + 2)
            if failure:
                return failure
        # A chamber is dug from the cell under its level, where the bot stays in the middle of the column: from the
        # cell beside the chamber it would mine the next one standing on the chamber's floor and not fall in.
        if p.y + 1 in m["level"]:
            failure = dig_landing(m["level"][p.y + 1], p, opened)
            if failure:
                return failure
        above = p
    deepest = m["level"][cells[-1].y]
    failure = dig_landing(deepest, cells[-1], opened)
    if failure:
        return failure
    goto(deepest["landing"])
    return ladders(cells, cells[-1].y) or result()


def to_level(level):
    """Climbs to the landing of level of the shaft in memory; gives goto's result."""
    m = shaft()
    assert m is not None and level in m["level"], f"no shaft with a level at {level}: dig_shaft first"
    return goto(m["level"][level]["landing"])


def to_surface():
    """Climbs out of the shaft in memory onto the ground beside its top; gives goto's result."""
    m = shaft()
    assert m is not None, "no shaft: dig_shaft first"
    return goto(m["top"], near=1)
