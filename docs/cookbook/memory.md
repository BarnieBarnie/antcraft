# Survive reloads and respawns

A running script is saved with the world: after a server restart or a reload the bot goes on at the same line, inside the action it was in, with its variables. Two things start it again from the top instead, with the same `ARGS`: a death (the bot runs its script again when it respawns) and an edit of the script or a module it imports. Only [`memory`](../reference/types/memory.md) survives those. So a long job keeps its progress there, and the top of the script reads it back.

This tunnel digger digs east from where it is started, two blocks high, and puts a torch every 8 blocks. How far it got is in `memory`, so after a death, or after you changed the script, it walks back into the tunnel and goes on where it stopped instead of starting a new tunnel.

```python
# Digs a tunnel two blocks high eastwards from where it is started, ARGS[0]
# blocks long (64 without), with a torch every 8 blocks. Its progress is kept in
# memory: after a respawn or an edit of this script it goes on where it stopped.
LENGTH = int(ARGS[0]) if ARGS else 64

if "tunnel" not in memory:
    memory["tunnel"] = {"start": pos(), "dug": 0}
start = memory["tunnel"]["start"]
if memory["tunnel"]["dug"] > 0:
    say(f"back to block {memory['tunnel']['dug']} of my tunnel")


def is_open(p):
    b = block(p)
    return b is not None and b.is_air


while memory["tunnel"]["dug"] < LENGTH:
    n = memory["tunnel"]["dug"] + 1
    feet = start.east(n)
    for p in [feet, feet.up()]:
        b = block(p)
        if b is not None and b.is_fluid:
            say(f"water or lava at {p}: I stop here")
            stop()
        if b is not None and not b.is_air:
            r = mine(p)
            if not r and r.reason in ("protected", "no_tool"):
                say(f"cannot dig {p}: {r.reason}")
                stop()
    if not (is_open(feet) and is_open(feet.up())):
        wait(20)  # gravel fell in, or a reflex took over: try again
        continue
    memory["tunnel"]["dug"] = n
    if n % 8 == 0 and has("torch"):
        place(start.east(n - 1), "torch")

say(f"the tunnel is {LENGTH} blocks long")
memory.pop("tunnel")
```

## How it works

- The first run stores the start, [`pos()`](../reference/movement/pos.md), and a count of the blocks dug in `memory["tunnel"]`. A run from the top finds them there and skips the setup. [`say`](../reference/bot/say.md) tells you it went back to work.
- The count goes up only once both blocks of a step are open, read back with [`block`](../reference/world/block.md). A [`mine`](../reference/blocks/mine.md) that was interrupted, or gravel that fell into the hole, leaves the count as it is and the step is done again. A block that cannot be dug (`protected`, such as bedrock, or `no_tool`, such as stone without a pickaxe) ends the script, as does water or lava ahead.
- `mine` walks to the block itself, so after a respawn the bot walks from its home back through the tunnel to the step it was at, on its own.
- The dict in `memory` is changed in place (`memory["tunnel"]["dug"] = n`), which `memory` allows for a list or dict it holds. Only plain data goes in: numbers, strings, [`Pos`](../reference/types/pos.md) values, and lists and dicts of these.
- [`place`](../reference/blocks/place.md) puts a torch on the floor of the step behind the bot. Without torches it digs on in the dark.
- At the end the script forgets its tunnel with `memory.pop("tunnel")`, so the next run digs a new one from where it is started.

What the bot digs goes into its inventory; once it is full, the rest lies on the ground. Add a [`put`](../reference/crafting/put.md) into a chest at `start`, or a [`drop`](../reference/inventory/drop.md), for a long tunnel.

For a respawn, also see [Recover items after death](recover-items.md): a bot that died in the tunnel comes back without its pickaxe and torches.

## Used here

[`memory`](../reference/types/memory.md), [`pos`](../reference/movement/pos.md), [`Pos`](../reference/types/pos.md), [`block`](../reference/world/block.md), [`Block`](../reference/types/block.md), [`mine`](../reference/blocks/mine.md), [`place`](../reference/blocks/place.md), [`has`](../reference/inventory/has.md), [`say`](../reference/bot/say.md), [`wait`](../reference/bot/wait.md), [`stop`](../reference/bot/stop.md), [`int`](../reference/python/int.md), [`dict` methods](../reference/types/dict.md). See also [Save and resume](../features/scripts.md#save-and-resume), [Budget & pausing](../language/budget.md#pausing-and-saving).
