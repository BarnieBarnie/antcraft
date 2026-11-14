# A job: strip-mines for an ore until the bot carries n of it, then comes back up and home. Run it as
# /antcraft bot run jobs.strip_mine <ore> [n] [y] [radius] [facing], such as jobs.strip_mine raw_iron 12: ore is a raw
# item of lib.dig.ORES, n 8 by default, y the height to mine at, lib.dig.best_y(ore) by default (emerald has none: give
# y), radius how far it looks for chests and the kit, 32, facing the way it digs, north (docs/design/library.md, Example
# jobs).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.bootstrap
import lib.dig
import lib.tools
from lib.borrow import borrowed, give_back
from lib.get import ensure_tool
from lib.home import go_home
from lib.result import result

# The kit a miner starts with: a pickaxe, a crafting table to make the next one with, torches for the branches.
MINER = [("stone_pickaxe", 1), ("crafting_table", 1), ("torch", 16)]
# How long each branch is, one torch at its end, and how many it digs before it gives up.
LENGTH = 8
ROUNDS = 16
# The blocks a branch breaks: its tunnel two blocks tall and a few ore. ponytail: a guess, not counted from the veins.
USES = 2 * LENGTH + 8

item = lib.dig.short(ARGS[0])
n = int(ARGS[1]) if len(ARGS) > 1 else 8
y = int(ARGS[2]) if len(ARGS) > 2 else lib.dig.best_y(item)
radius = int(ARGS[3]) if len(ARGS) > 3 else 32
facing = ARGS[4] if len(ARGS) > 4 else "north"


def tier():
    """The pickaxe tier the ore of item needs ("iron" for diamond), stone at least: the kit's."""
    t = lib.tools.tier_for(item.replace("raw_", "").replace("_lazuli", "") + "_ore")
    return min([t, "stone"], key=lib.tools.TIERS.index)


def strip_mine():
    """Gets the kit, digs stairs down to y towards facing and branches on until the bot carries n of item, making sure before the stairs
    and each branch that its pickaxe lasts them, then climbs back up and goes home. Where the stairs or a branch reach
    the edge of lib.find.WHERE, it turns square to it (lib.dig.turn). Gives result()."""
    if item not in lib.dig.ORES:
        return result("no_recipe", None, {"minecraft:" + item: n})
    if y is None:
        return result("no_ore", None, {"minecraft:" + item: n})  # as lib.dig.ore: no height to dig to (emerald)
    r = lib.bootstrap.kit(MINER, radius)
    if r["ok"]:
        r = ensure_tool("pickaxe", 3 * max(pos().y - y, 0), tier(), radius)
    way = facing
    tried = []
    if r["ok"]:
        r = lib.dig.stairs_to(y, way)
    for _ in range(ROUNDS):
        while r["reason"] == "outside" and way is not None:
            # The edge of lib.find.WHERE ends a branch or the stairs: it goes on square to it, as lib.dig.ore (#561).
            tried.append(way)
            way = lib.dig.turn(way, tried)
            r = lib.dig.stairs_to(y, way) if way is not None else result()
        if not r["ok"] or count(item) >= n or way is None:
            break
        # A new pickaxe where the one it holds would break in the branch: stone from the cobblestone it dug.
        r = ensure_tool("pickaxe", USES, tier(), radius)
        if r["ok"]:
            r = lib.dig.branch(LENGTH, facing=way)
    if r["reason"] == "outside":
        r = result()  # the last branch ended at the edge: no_ore below where it carries too few
    if lib.dig.stairs() is not None:
        up = lib.dig.stairs_up()
        r = r if not r["ok"] else up
    go_home()
    if r["ok"] and count(item) < n:
        return result("no_ore", None, {"minecraft:" + item: n - count(item)})
    return r


try:
    r = strip_mine()
    if r["ok"]:
        print(f"strip mined {count(item)} {item}")
    else:
        warn("strip mine:", r["reason"], r["at"], r["missing"])
finally:
    give_back()
    if borrowed():
        warn("could not give back", borrowed())
