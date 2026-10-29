# `portals`

```text
portals()
```

Both ends of the nether portals the bot went through, newest first.

## Returns

A list of [`Pos`](../types/pos.md), each with its `dimension`: for each portal the bot went through, by [`travel`](travel.md) or by walking in, the block it went into and the one it came out on.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

A portal remembered replaces one in the same dimension within 16 blocks of it along each axis (the same portal, or one beside it), so going back and forth through one portal keeps two entries. The bot keeps the newest 8. They are saved with the bot and kept when it dies.

The bot remembers portals only as it goes through them, and forgets one when `travel` finds it gone (`no_portal`). The end portal is not remembered. See [Nether travel](../../features/nether.md#travelling-between-dimensions).

## Examples

The nearest portal remembered in the dimension the bot is in:

```python
nearest = None
for p in portals():
    if p.dimension == dimension() and (nearest is None or pos().distance(p) < pos().distance(nearest)):
        nearest = p
```

## See also

[`travel`](travel.md), [`dimension`](dimension.md), [`find_nearest`](../world/find_nearest.md), [Nether travel](../../features/nether.md).
