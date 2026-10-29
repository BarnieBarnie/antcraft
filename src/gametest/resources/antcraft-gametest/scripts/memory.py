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
