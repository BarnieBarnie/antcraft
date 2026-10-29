# A hauler: keeps the nearest chest stocked with STOCK of each item, taken from
# the nearest barrel.
STOCK = {"minecraft:torch": 64, "minecraft:bread": 16}

if "target" not in memory:
    chest = find_nearest("chest", radius=8)
    barrel = find_nearest("barrel", radius=8)
    if chest is None or barrel is None:
        say("put a chest and a barrel near me")
        stop()
    memory["target"] = chest
    memory["source"] = barrel
target = memory["target"]
source = memory["source"]


def stocked(at, item):
    """How many of item the container at `at` holds."""
    stacks = container(at) or []
    return sum([s.count for s in stacks if matches(s, item)])


while True:
    for item, want in STOCK.items():
        short = want - stocked(target, item)
        if short > 0 and count(item) < short:
            r = take(source, item, short - count(item))
            if not r:
                warn(f"no {item} to take: {r.reason}")
        if short > 0 and has(item):
            put(target, item, min(short, count(item)))
        if has(item):
            put(source, item)  # what did not fit goes back
    wait(200)
