# Picking the best of several enchantable items, for any script: `import lib.enchant`, then
# lib.enchant.best_rod(inventory()). A module of the mod's default library (lib/), a finder: it takes no tick.
# (docs/reference/modules/lib/enchant.md)
#
# Each kind of item values its own enchantments (weights, enchantment id to weight); the scoring is shared.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.tools import uses_left

# The highest level of each enchantment the weights name. ponytail: listed by hand, as a Stack gives only the level;
# add an enchantment here with its weights.
MAX = {"minecraft:lure": 3, "minecraft:luck_of_the_sea": 3, "minecraft:mending": 1, "minecraft:unbreaking": 3}

# A fishing rod's: Lure catches sooner, Luck of the Sea more treasure, Mending and Unbreaking keep it.
ROD = {"minecraft:lure": 3, "minecraft:luck_of_the_sea": 2, "minecraft:mending": 2, "minecraft:unbreaking": 1}


def score(stack, weights):
    """The sum over weights of weight x level / max level of the stack's enchantments: every weighted one at its max scores highest."""
    total = 0
    for e in weights:
        total += weights[e] * min(stack.enchantments.get(e, 0), MAX[e]) / MAX[e]
    return total


def better(a, b, weights):
    """Whether Stack a beats Stack b: a higher score, or the same and more uses left."""
    sa = score(a, weights)
    sb = score(b, weights)
    return sa > sb or (sa == sb and uses_left(a) > uses_left(b))


def best(stacks, weights):
    """The Stack of stacks that beats the others, None for none."""
    top = None
    for s in stacks:
        if top is None or better(s, top, weights):
            top = s
    return top


def best_rod(stacks):
    """The best fishing rod of stacks by ROD, other items left out; None where there is none."""
    return best([s for s in stacks if s.item == "minecraft:fishing_rod"], ROD)
