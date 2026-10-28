# Mining the levels of a ladder shaft, for any script: `import lib.mine`, then
# lib.mine.mine_level(20) once lib.shaft.dig_shaft dug down to level 20.
# A module of the mod's default library (lib/).
#
# Each level has a hall from its landing and rounds of branches off the hall
# (see mineshaft_at). The shaft is the one kept in memory["shaft"]; how many
# rounds of a level are done is kept in memory["mine"], how far a round is dug
# is read from the world.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.shaft import dig, seal, put_block, result, FILL, TORCHES

# What mining ore gives, which goes into the landing chest.
ORES = ["minecraft:coal", "minecraft:raw_iron", "minecraft:raw_copper", "minecraft:raw_gold", "minecraft:redstone",
        "minecraft:lapis_lazuli", "minecraft:diamond", "minecraft:emerald", "minecraft:quartz", "minecraft:ancient_debris"]
# How much of what it digs it keeps to wall fluid off with; the rest goes into the chest too.
KEEP = 64
# ponytail: a bot carries 27 stacks and its hand; it empties itself with a few slots free, as one block of a vein may
# drop more than one kind.
FULL = 24


def site(r=0):
    """The shaft in memory["shaft"] with its branches of round r. Besides top and levels, memory["shaft"] may hold
    branches (per side, default 4) and length (of a branch, default 16) for a shaft of another size."""
    kept = memory.get("shaft")
    if kept is None:
        return None
    return mineshaft_at(kept["top"], kept["levels"], round=r, branches=kept.get("branches", 4), length=kept.get("length", 16))


def report(reason=None, at=None, missing=None, sealed=None):
    """What mine_level gives: lib.shaft's result, and sealed, the fluid blocks it sealed, where a branch stopped."""
    r = result(reason, at, missing)
    r["sealed"] = sealed or []
    return r


def dug(p):
    b = block(p)
    return b is not None and (b.is_air or b.id in TORCHES)


def unload(chest):
    """Puts the ore it carries and what it dug beyond KEEP into the chest; None once done, else a result."""
    for item in ORES:
        if has(item):
            r = put(chest, item)
            if not r:
                return result(r.reason, chest)
    spare = -KEEP
    for item in FILL:
        spare += count(item)
    for item in FILL:
        n = min(spare, count(item))
        if n > 0:
            r = put(chest, item, n)
            if not r:
                return result(r.reason, chest)
            spare -= n
    return None


def room(chest):
    """Empties the bot into the chest when its inventory is nearly full."""
    if len(inventory()) >= FULL:
        return unload(chest)
    return None


def fluids(cells):
    """The fluid blocks in cells and beside them."""
    wet = []
    for c in cells:
        for p in (c, c.north(), c.south(), c.east(), c.west(), c.up(), c.down()):
            b = block(p)
            if b is not None and b.is_fluid and p not in wet:
                wet.append(p)
    return wet


def veins(cells, opened, stand, chest):
    """Mines every ore vein in the walls and ceiling of cells, standing at stand, and walls off what each opens up."""
    for c in cells:
        for p in (c.north(), c.south(), c.east(), c.west(), c.up()):
            if p in opened:
                continue
            vein = ore_vein(p)
            for b in vein:
                failure = room(chest) or dig(b, opened + vein, stand)
                if failure:
                    return failure
    return None


def tunnel(cells, stand, opened, level, sealed):
    """Digs cells (feet and head, from stand outwards), mining the veins it uncovers and putting torches at the
    level's torch spots. Where a fluid is in or beside the next block, it seals it, adds it to sealed and stops
    there: short of a fluid in the next block, past the block beside the fluid."""
    for i in range(0, len(cells), 2):
        feet = cells[i]
        head = cells[i + 1]
        if dug(feet) and dug(head):
            stand = feet
            continue
        failure = room(level["chest"])
        if failure:
            return failure
        # A fluid is sealed once in sight: in the next block from the one before it, beside it once it is dug.
        wet = fluids([feet, head])
        if feet in wet or head in wet:
            for p in (head, feet):
                failure = seal(p, stand) if p in wet else None
                if failure:
                    return failure
            sealed.append(feet if feet in wet else head)
            return None
        dry = [p for p in opened if p not in wet]
        for p in (head, feet):
            failure = dig(p, dry, stand)
            if failure:
                return failure
        if wet:
            sealed.append(wet[0])
            return None
        failure = veins([feet, head], opened, feet, level["chest"])
        if failure:
            return failure
        if feet in level["torches"]:
            failure = put_block(feet, "torch", TORCHES)
            if failure:
                return failure
        stand = feet
    return None


def mine_level(level, rounds=1):
    """Digs the hall of level of the shaft in memory (dug by lib.shaft) and the next rounds of branches off it,
    mines every ore vein it uncovers, puts torches at the torch spots and the ore into the landing chest, also when
    its inventory fills. Where a fluid is in or beside the next block of a hall or branch, it seals it and goes on with
    the next branch. Gives report(): ok, or the reason it stopped (missing_ingredients without a pickaxe or
    something to seal with, no_space when the chest is full, or an action's reason) with at, and sealed."""
    m = site()
    assert m is not None and level in m["level"], f"no shaft with a level at {level}: dig_shaft first"
    if not has("#minecraft:pickaxes"):
        return report("missing_ingredients", None, {"#minecraft:pickaxes": 1})
    here = m["level"][level]
    chest = here["chest"]
    hall = []
    for h in here["hall"]:
        hall = hall + [h, h.up()]
    base = [here["landing"], here["landing"].up(), chest] + hall
    sealed = []
    done = memory.get("mine", {})
    for _ in range(rounds):
        r = done.get(level, 0)
        here = site(r)["level"][level]
        before = hall
        if r > 0:
            before = []
            for cells in site(r - 1)["level"][level]["branches"]:
                before = before + cells
        opened = base + before
        for cells in here["branches"]:
            opened = opened + cells
        failure = tunnel(hall, here["landing"], opened, here, sealed)
        for cells in here["branches"]:
            if failure:
                break
            stands = [p for p in before if p.distance(cells[0]) == 1 and p.y == cells[0].y]
            # A branch whose way in stopped short at a fluid is left alone.
            if stands and dug(stands[0]):
                failure = tunnel(cells, stands[0], opened, here, sealed)
        if failure:
            return report(failure["reason"], failure["at"], failure["missing"], sealed)
        done[level] = r + 1
        memory["mine"] = done
    failure = unload(chest)
    if failure:
        return report(failure["reason"], failure["at"], None, sealed)
    return report(sealed=sealed)
