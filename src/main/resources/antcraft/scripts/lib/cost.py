# The numbers the library's estimates use, for any script: `import lib.cost`, then lib.cost.walk(a, b).
# A module of the mod's default library (lib/), a primitive: finders only, it imports nothing.
#
# ponytail: constants, not the game's break-time formula; a break_ticks(pos, item) sense if the estimates prove too rough.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

# Ticks the bot takes to walk a block.
WALK = 5

# Ticks to break a block of a material with a tool of a tier ("hand" for none): stone (hardness 1.5, pickaxe), ore
# (3, pickaxe), wood (2, axe) and dirt (0.5, shovel), as the game times a tool that drops the block. With a pickaxe too
# low a tier for an ore the block breaks slower and drops nothing (see lib.tools.tier_for).
BREAK = {
    "stone": {"hand": 150, "wooden": 23, "stone": 12, "copper": 9, "iron": 8, "diamond": 6, "netherite": 5, "golden": 4},
    "ore": {"hand": 300, "wooden": 45, "stone": 23, "copper": 18, "iron": 15, "diamond": 12, "netherite": 10, "golden": 8},
    "wood": {"hand": 60, "wooden": 30, "stone": 15, "copper": 12, "iron": 10, "diamond": 8, "netherite": 7, "golden": 5},
    "dirt": {"hand": 15, "wooden": 8, "stone": 4, "copper": 3, "iron": 3, "diamond": 2, "netherite": 2, "golden": 2},
}

# The blocks a tool of a tier breaks before it is used up.
USES = {"wooden": 59, "stone": 131, "copper": 190, "iron": 250, "diamond": 1561, "golden": 32, "netherite": 2031}

# Ticks a furnace takes to smelt an item.
SMELT = 200


def walk(a, b):
    """The ticks the bot takes to walk from Pos a to Pos b: WALK for each block along x, y and z."""
    # ponytail: the blocks along each axis, not a path; reachable() or goto tell the real way.
    return WALK * (abs(a.x - b.x) + abs(a.y - b.y) + abs(a.z - b.z))


def breaking(n, material, tier):
    """The ticks the bot takes to break n blocks of material ("stone", "ore", "wood" or "dirt") with a tool of tier
    ("wooden", "stone", "copper", "iron", "diamond", "netherite", "golden", or "hand" or None for none)."""
    return n * BREAK[material][tier or "hand"]
