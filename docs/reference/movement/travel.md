# `travel`

```text
travel(portal)
```

Walks into the nether portal at `portal` and goes through it to the other dimension.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `portal` | [`Pos`](../types/pos.md) or tuple | | any block of the portal, such as `find_nearest("nether_portal")` or an entry of `portals()` gives |

## Returns

A [`Result`](../types/result.md), ok once the bot is in the other dimension. Its `value` is `None`.

## Pauses

Yes, until the bot is through or gives up. The script goes on in the other dimension, inside `travel`: the game makes the bot anew there from its save, and the new bot carries on with the same script.

## Cost

One instruction, the call itself. The walk draws on the server's pathfinding budget, as [`goto`](goto.md) does.

## Description

The bot walks, as `goto` does and loading chunks the same way, to the bottom block of the portal's column, and steps in. The portal takes it on the next tick; if it has not gone within a second (20 ticks) of standing in the portal, `travel` fails with `timeout`. The game keeps a mob that came through a portal from going back for 15 seconds; `travel` lifts that, so a bot can go back at once.

Once the block at `portal` is loaded and is no nether portal, before or during the walk, `travel` fails with `no_portal`, and the bot forgets every portal it remembers in its dimension within 16 blocks of `portal` along each axis (see [`portals`](portals.md)). A portal in a chunk not loaded yet is not forgotten until the walk loads it.

Only nether portals work; the end portal is not supported. See [Nether travel](../../features/nether.md#travelling-between-dimensions).

## Examples

Go through the nearest portal, or else the newest one remembered in this dimension:

```python
portal = find_nearest("nether_portal")
if portal is None:
    for p in portals():
        if p.dimension == dimension():
            portal = p
            break
if portal is not None and travel(portal):
    say("now in " + dimension())
```

## Fails with

| Reason | When |
|---|---|
| `no_portal` | the block at `portal` is loaded and is no nether portal |
| `timeout` | the bot stood in the portal a second without going, or got no closer on its way for 2400 ticks |
| `unreachable` | the pathfinder finds no way to the portal |
| `not_loaded` | a chunk on the way did not load, as for `goto` |
| `wrong_dimension` | `portal` is in another dimension than the bot |
| `interrupted` | a [reflex](../../features/bots.md#reflexes) took over |

## See also

[`portals`](portals.md), [`dimension`](dimension.md), [`home_dimension`](home_dimension.md), [`goto`](goto.md), [`find_nearest`](../world/find_nearest.md), [Nether travel](../../features/nether.md).
