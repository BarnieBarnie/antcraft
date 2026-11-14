# The tools the bot carries, for any script: `import lib.tools`, then lib.tools.enough("axe", 50).
# A module of the mod's default library (lib/), a skill: only hold takes a tick, the rest are finders.
#
# A kind is "pickaxe", "axe", "shovel", "hoe" or "sword"; its tools are those of the item tag #minecraft:<kind>s.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.result import result

# Tool tiers from best to worst. ponytail: ranked by the tier in the id; copper drops what stone does but mines faster
# and lasts longer (#578), golden comes after stone; a modded tool of no listed tier counts as worse than these.
TIERS = ["netherite", "diamond", "iron", "copper", "stone", "golden", "wooden"]

# The lowest pickaxe tier each block needs to drop anything, by the game's needs_*_tool block tags; any other block
# drops with a wooden one. Only for a block id: tier_for of a Pos reads the block's tags, which modded blocks are in too.
NEEDS = {}
for b in ["iron_ore", "deepslate_iron_ore", "raw_iron_block", "iron_block", "copper_ore", "deepslate_copper_ore",
          "raw_copper_block", "copper_block", "lapis_ore", "deepslate_lapis_ore", "lapis_block"]:
    NEEDS[b] = "stone"
for b in ["diamond_ore", "deepslate_diamond_ore", "diamond_block", "gold_ore", "deepslate_gold_ore", "raw_gold_block",
          "gold_block", "redstone_ore", "deepslate_redstone_ore", "emerald_ore", "deepslate_emerald_ore", "emerald_block"]:
    NEEDS[b] = "iron"
for b in ["obsidian", "crying_obsidian", "respawn_anchor", "ancient_debris", "netherite_block"]:
    NEEDS[b] = "diamond"


def tier(stack):
    """The tier in a tool's id ("stone" for minecraft:stone_axe), or None for a tool of no listed tier."""
    for t in TIERS:
        if stack.item.startswith(f"minecraft:{t}_"):
            return t
    return None


def rank(stack):
    """A tool's index in TIERS, len(TIERS) for one of no listed tier: lower is better."""
    t = tier(stack)
    return TIERS.index(t) if t else len(TIERS)


def uses_left(stack):
    """How many more blocks a tool Stack breaks before it is used up."""
    return stack.max_damage - stack.damage


def carried(kind):
    """The Stacks of tools of kind the bot carries, held one included, best first by TIERS."""
    return sorted([s for s in inventory() if matches(s, f"#minecraft:{kind}s")], key=rank)


def best(kind):
    """The best tool of kind the bot carries, a Stack, by TIERS; None where it carries none."""
    tools = carried(kind)
    return tools[0] if tools else None


def enough(kind, uses):
    """Whether the tools of kind the bot carries together last uses more blocks."""
    return sum([uses_left(s) for s in carried(kind)]) >= uses


_BY_TAGS = {}  # tier_for of the block ids NEEDS leaves out, read once from their tags


def tier_for(what):
    """The lowest pickaxe tier that drops what ("iron" for "minecraft:diamond_ore"); "wooden" for a block any pickaxe
    drops. what is a block id, looked up in NEEDS, else in its block's needs_*_tool tags (block_tags), or a Pos, whose
    block's tags tell; so modded blocks have their tier too."""
    if isinstance(what, str):
        name = what.replace("minecraft:", "")
        if name in NEEDS:
            return NEEDS[name]
        if what not in _BY_TAGS:
            _BY_TAGS[what] = _tier_of(block_tags(what))
        return _BY_TAGS[what]
    return _tier_of(block(what).tags)


def _tier_of(tags):
    for t in ["diamond", "iron", "stone"]:
        if f"#minecraft:needs_{t}_tool" in tags:
            return t
    return "wooden"


def drops(p):
    """Whether the best pickaxe the bot carries is of a tier that drops the block at p (tier_for); also for one of no
    listed tier, which mine itself then tells about. False where it carries none."""
    t = best("pickaxe")
    return t is not None and (tier(t) is None or rank(t) <= TIERS.index(tier_for(p)))


def hold(kind):
    """Equips the best tool of kind the bot carries, by TIERS; one of no listed tier only where it carries none of these.
    Gives result()."""
    for t in TIERS:
        if equip(f"{t}_{kind}"):
            return result()
    equip(f"#minecraft:{kind}s")
    return result()
