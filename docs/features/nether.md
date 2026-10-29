# Nether travel

**Nether.** Bots go between the Overworld and the Nether through nether portals as players do: `travel(portal)` walks into one, and the script carries on in the other dimension. A bot remembers both ends of each portal it went through (`portals()`, the newest 8, kept through death) and forgets one it finds broken. Positions carry their dimension, and an action given a position in another dimension fails with `wrong_dimension`. The end portal is not supported.

## Travelling between dimensions

A bot goes between the Overworld and the Nether through a nether portal, as a player does: `travel(portal)` walks into the portal, which takes the bot at once (mobs do not wait in a portal as players do), and the bot comes out of the portal on the other side, which the game finds or builds as for a player. The game keeps a mob that came through a portal from going back for 15 seconds, and for as long as it stands in a portal; `travel` lifts that, so a bot can go back at once. Other walks (`goto`, `go_home`, work, fleeing) never step into a nether portal or dig at its frame: they go round it, or find no way if it is the only one. The script goes on in the other dimension, inside `travel`, which then gives ok: the game makes the bot anew there from its save, and the new bot carries on with the same script, the running action and all (see [Save and resume](scripts.md#save-and-resume)), also a script not from the script library. Bots find a portal with `find_nearest("nether_portal")`, within 32 blocks, or in `portals()`: a bot remembers both ends of each nether portal it goes through, by `travel` or by walking in, the block of the portal it went into and the one it came out on, each a `Pos` with its `dimension`. They are saved with the bot and kept when it dies. A portal remembered replaces one in the same dimension within 16 blocks along each axis (the same portal, or one beside it), so going back and forth through one portal keeps two entries, and a bot keeps the newest 8. It remembers portals only as it goes through them, and forgets one when `travel` finds it gone: once the block at the position given is loaded and is no nether portal, before or while the bot walks there, `travel` stops and fails with `no_portal`, and every portal remembered in the bot's dimension within 16 blocks of that position along each axis (the same portal) leaves `portals()`. A portal in a chunk not loaded yet is not forgotten until the walk loads it. A remembered portal may be far and in unloaded chunks: `travel` walks there as `goto` does, loading the chunks on its way (with `botsLoadChunks` on; `not_loaded` otherwise). The end portal is not supported, nor remembered. In another dimension than its home's, `go_home` in `lib/home.py`, and `idle.py` (which has its own copy, so a changed or broken `lib/home.py` does not change idle bots), goes through a nether portal when the home is in the Overworld or the Nether: the nearest within 32 blocks, or else the nearest it remembers in the dimension it is in (`portal_out()`), the next nearest when that one is gone (`no_portal`), and walks home from where it comes out:

```python
def portal_out():
    portal = find_nearest("nether_portal")
    if portal is None:
        for p in portals():
            if p.dimension == dimension() and (portal is None or pos().distance(p) < pos().distance(portal)):
                portal = p
    return portal

if dimension() != home_dimension():
    portal = portal_out()
    while portal is not None:
        result = travel(portal)
        if result.reason != "no_portal":
            break
        portal = portal_out()  # the gone one is forgotten: the next
    if portal is not None and result:
        goto(home())
```
