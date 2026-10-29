# Recover items after death

A dead bot drops what it carried where it died and respawns at its home ten seconds later, running its script again from the top (see [Death and respawn](../features/death.md)). This lumberjack checks at the top whether it has died since it last looked, and if so walks back for its things before it goes on chopping.

```python
# A lumberjack: chops the trees around its home and puts the logs in the chest
# by its home. After a death it first fetches what it dropped.
from lib.home import go_home


def recover():
    """Walks back to where the bot last died and picks up what it dropped there."""
    p = last_death()
    if p is None:
        return
    if p.dimension != dimension():
        warn("I died in", p.dimension, "and cannot fetch my things from here")
        return
    r = goto(p, near=2)
    if not r:
        warn("cannot get back to where I died:", r.reason)
        return
    got = pick_up(radius=8)
    say(f"picked up {got.value} items where I died")


# The first run only takes note of the deaths so far.
if "deaths" not in memory:
    memory["deaths"] = deaths()
if deaths() > memory["deaths"]:
    memory["deaths"] = deaths()
    recover()
    go_home()

chest = find_nearest("chest", radius=8)
if chest is None:
    say("put a chest by my home")
    stop()

while True:
    log = find_nearest("#minecraft:logs", radius=24)
    if log is None:
        wait(1200)  # a minute; saplings may have grown by then
        continue
    r = mine(log)
    if not r:
        warn(f"cannot chop {log}: {r.reason}")
        wait(200)
    if count("#minecraft:logs") >= 32 or free_slots() < 2:
        put(chest, "#minecraft:logs")
```

## How it works

- [`deaths()`](../reference/bot/deaths.md) counts the bot's deaths and is saved with it; the count the script last saw is kept in [`memory`](../reference/types/memory.md), which a death does not clear. When `deaths()` is higher, the bot has respawned. The count is stored before `recover()` runs, so a bot that dies again on the way back tries again for its newest death, not the old one.
- [`last_death()`](../reference/bot/last_death.md) is where it died, a [`Pos`](../reference/types/pos.md) with the dimension. The bot respawns in its home's dimension, so a death in the Nether is out of reach for [`goto`](../reference/movement/goto.md), which would fail with `wrong_dimension`; the script says so and leaves it.
- [`pick_up`](../reference/inventory/pick_up.md) walks to the dropped items within 8 blocks and takes them. Its `value` is how many it took, also when it fails: dropped items vanish after five minutes, as for a player, and lava burns them.
- [`go_home`](../reference/modules/lib/home.md#go_hometries3) walks back, and the chest is looked up from there.
- A reload or restart does not run the script from the top: the bot resumes where it was (see [Save and resume](../features/scripts.md#save-and-resume)), so the death check runs only after a respawn, or after the script was edited.

## Used here

[`deaths`](../reference/bot/deaths.md), [`last_death`](../reference/bot/last_death.md), [`memory`](../reference/types/memory.md), [`dimension`](../reference/movement/dimension.md), [`goto`](../reference/movement/goto.md), [`pick_up`](../reference/inventory/pick_up.md), [`lib/home`](../reference/modules/lib/home.md), [`find_nearest`](../reference/world/find_nearest.md), [`mine`](../reference/blocks/mine.md), [`count`](../reference/inventory/count.md), [`free_slots`](../reference/inventory/free_slots.md), [`put`](../reference/crafting/put.md), [`say`](../reference/bot/say.md), [`warn`](../reference/bot/warn.md), [`wait`](../reference/bot/wait.md), [`stop`](../reference/bot/stop.md).
