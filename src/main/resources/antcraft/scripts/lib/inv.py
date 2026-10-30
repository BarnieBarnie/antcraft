# Taking what the bot lacks from a chest, for any script: `from lib.inv import take_all`,
# or `import lib.inv` and call lib.inv.take_all(chest, need). A module of the mod's
# default library (lib/).
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.


def take_all(chest, need):
    """Takes from chest until the bot carries each count of need (an item id or a selector such as "#minecraft:planks"
    to how many), as far as the chest has it; gives what is still short, item to how many ({} when nothing is)."""
    short = {}
    for item, n in need.items():
        if count(item) < n and any([matches(s, item) for s in container(chest) or []]):
            take(chest, item, n - count(item))
        if count(item) < n:
            short[item] = n - count(item)
    return short
