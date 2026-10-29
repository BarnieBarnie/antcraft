# Mining loop

A miner that digs a ladder shaft beside the chest it starts by, then mines its levels in turns for ever, a round of branches at a time, and brings the ore up to the chest. It takes its pickaxes, torches and what the shaft needs (ladders, a wooden trapdoor, chests) from that chest too. The digging is done by the default library's [`lib/shaft`](../reference/modules/lib/shaft.md) and [`lib/mine`](../reference/modules/lib/mine.md); this script only keeps them supplied.

```python
# A miner: digs a ladder shaft by the chest it starts at, then mines the levels
# in turns and brings the ore up to that chest. Tools and supplies come from it.
import lib.mine
import lib.shaft

LEVELS = [16, -16]  # iron, then diamonds further down

if "store" not in memory:
    chest = find_nearest("chest", radius=8)
    if chest is None:
        say("put a chest by me with a pickaxe, ladders, a trapdoor, torches and chests")
        stop()
    memory["store"] = chest
store = memory["store"]


def fetch(item, n):
    """Takes items from the store until the bot carries n of item; gives whether it does."""
    if count(item) < n:
        take(store, item, n - count(item))
    return count(item) >= n


# Dig the shaft; a call again goes on where the last one stopped.
while True:
    fetch("#minecraft:pickaxes", 1)
    r = lib.shaft.dig_shaft(LEVELS)
    if r["ok"]:
        break
    if r["reason"] == "no_site":
        say("there is no room for a shaft here")
        stop()
    warn(f"the shaft stopped: {r['reason']} at {r['at']}, missing {r['missing']}")
    for item, n in r["missing"].items():
        fetch(item, count(item) + n)
    wait(100)

while True:
    site = lib.mine.site()
    for y in site["levels"]:
        fetch("#minecraft:pickaxes", 1)
        fetch("torch", len(site["level"][y]["torches"]))
        lib.shaft.to_level(y)
        r = lib.mine.mine_level(y)
        take(site["level"][y]["chest"], lib.mine.ORES)
        lib.shaft.to_surface()
        put(store, lib.mine.ORES)
        if not r["ok"]:
            warn(f"level {y} stopped: {r['reason']} at {r['at']}, missing {r['missing']}")
            wait(200)
```

## How it works

- [`lib.shaft.dig_shaft`](../reference/modules/lib/shaft.md#dig_shaftlevels) picks a site beside the bot with [`mineshaft`](../reference/mining/mineshaft.md), keeps it in `memory["shaft"]` and digs it. Before it digs it checks what it carries, and when something is short it gives `missing_ingredients` with `missing`, a dict of item (or tag) to how many more it needs; `fetch` takes those from the store and the loop calls it again. How far the shaft is dug is read from the world, so each call goes on where the last stopped, also after a restart.
- `mineshaft` may keep fewer heights than asked for, so the mining loop goes through `lib.mine.site()["levels"]`, the levels the shaft really has, rather than `LEVELS`.
- [`lib.mine.mine_level`](../reference/modules/lib/mine.md#mine_levellevel-rounds1) mines one round of branches of a level, puts the ore into the landing chest of the level (the stone it does not keep it drops), and counts the rounds done in `memory["mine"]`, so the next call digs the next round.
- [`take`](../reference/crafting/take.md) with `lib.mine.ORES` (a list of item ids, a [selector](../reference/types/selectors.md)) empties the ore out of the landing chest; [`lib.shaft.to_surface`](../reference/modules/lib/shaft.md#to_surface) climbs out and [`put`](../reference/crafting/put.md) stores it.
- A failed level is logged with [`warn`](../reference/bot/warn.md) and tried again on the next turn: no pickaxe left (`missing_ingredients`), a landing chest full of ore (`no_space`), or an action that failed on the way.

The landing chests hold only ore, which this script empties each turn, so they do not fill up. To dig a new shaft elsewhere, forget the old one with `memory.pop("shaft")`. The default `colony.miner` script ([Default colony scripts](../features/colony-scripts.md)) shares one shaft between several miners through the job board.

## Used here

[`lib/shaft`](../reference/modules/lib/shaft.md), [`lib/mine`](../reference/modules/lib/mine.md), [`mineshaft`](../reference/mining/mineshaft.md), [`memory`](../reference/types/memory.md), [`find_nearest`](../reference/world/find_nearest.md), [`count`](../reference/inventory/count.md), [`take`](../reference/crafting/take.md), [`put`](../reference/crafting/put.md), [`say`](../reference/bot/say.md), [`warn`](../reference/bot/warn.md), [`wait`](../reference/bot/wait.md), [`stop`](../reference/bot/stop.md), [`len`](../reference/python/len.md), [`dict` methods](../reference/types/dict.md). See also [Mining](../features/mining.md).
