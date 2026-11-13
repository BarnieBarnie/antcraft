# Kits, the things a job starts with, for any script: `import lib.bootstrap`, then lib.bootstrap.kit().
# A module of the mod's default library (lib/), a kit: it imports the resolver, lib.get, and only jobs import it
# (docs/design/library.md, Kits).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.tools
from lib.get import get, ensure_tool
from lib.result import result

# What kit() gets, in order: the tools first, so the rest is gathered with them.
KIT = [("stone_pickaxe", 1), ("stone_axe", 1), ("stone_shovel", 1), ("stone_sword", 1), ("crafting_table", 1),
       ("furnace", 1), ("torch", 16)]
KINDS = ["pickaxe", "axe", "shovel", "hoe", "sword"]


def kit(items=KIT, radius=32):
    """Makes the bot carry each of items, a list of (item, n), in order, by get(item, n, radius); a tool, such as
    "stone_pickaxe", by ensure_tool instead, so a carried or chest tool of its kind and at least its tier does (borrowed).
    Gives result(): ok, or the reason the first that failed gave, with missing; what it got before it keeps."""
    for item, n in items:
        tier, kind = (item.split("_", 1) + [""])[:2]
        if tier in lib.tools.TIERS and kind in KINDS:
            r = ensure_tool(kind, 1, tier, radius)
        else:
            r = get(item, n, radius)
        if not r["ok"]:
            return r
    return result()

