# `pos`

```text
pos()
```

The bot's position: the block its feet are in.

## Returns

A [`Pos`](../types/pos.md) with the `dimension` the bot is in.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Examples

```python
here = pos()
print("at", here.x, here.y, here.z, "in", here.dimension)
if dimension() == home_dimension() and here.distance(home()) > 64:
    goto(home())
```

## See also

[`dimension`](dimension.md), [`home`](home.md), [`goto`](goto.md), [`Pos`](../types/pos.md).
