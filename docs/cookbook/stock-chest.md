# Keep a chest stocked

A hauler that keeps a chest topped up from a store: whenever the chest holds fewer than its share of an item, the bot fetches the difference from a barrel and puts it in. Start it between the two, with the chest (say, the one by your door) and the barrel (your store room) within 8 blocks.

Keep a chest stocked from a barrel:

```python
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
```

## How it works

- The two containers are looked up once with [`find_nearest`](../reference/world/find_nearest.md) and kept in [`memory`](../reference/types/memory.md), so the script finds the same ones after an edit, wherever the bot stands then. They are only stored once both are found: a `None` kept in memory would stop it from looking again.
- [`container`](../reference/crafting/container.md) reads what the chest holds from wherever the bot is, as a list of [`Stack`](../reference/types/stack.md) values, or `None` when there is no container there. [`matches`](../reference/inventory/matches.md) tells whether a stack is the item, so `STOCK` may also name a tag such as `"#minecraft:logs"`.
- [`take`](../reference/crafting/take.md) and [`put`](../reference/crafting/put.md) walk to the container and move at most `n` items. `n` must be at least 1, hence the checks before each call. A take that finds none fails with `no_item`, which the script logs with [`warn`](../reference/bot/warn.md) and tries again next round.
- What the bot still carries after the round, because the chest was full or the bot had some already, goes back to the barrel, so its inventory never fills up.

## Used here

[`find_nearest`](../reference/world/find_nearest.md), [`memory`](../reference/types/memory.md), [`container`](../reference/crafting/container.md), [`matches`](../reference/inventory/matches.md), [`take`](../reference/crafting/take.md), [`put`](../reference/crafting/put.md), [`count`](../reference/inventory/count.md), [`has`](../reference/inventory/has.md), [`say`](../reference/bot/say.md), [`warn`](../reference/bot/warn.md), [`wait`](../reference/bot/wait.md), [`stop`](../reference/bot/stop.md), [`sum`](../reference/python/sum.md), [`min`](../reference/python/min.md), [`dict` methods](../reference/types/dict.md). Colonies move items with jobs instead: see `colony.hauler` in [Default colony scripts](../features/colony-scripts.md).
