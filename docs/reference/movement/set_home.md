# `set_home`

```text
set_home(pos)
```

Sets the bot's home.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | the new home |

## Returns

`None`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

The home is set in the `dimension` of `pos`, or in the dimension the bot is in when `pos` has none (a tuple, or a `Pos` the script made). It is the only function that takes a position from another dimension than the bot's. The home and its dimension are saved with the bot; the bot respawns there and an idle bot walks there (see [Idle, home and death](../../features/death.md#idle-home-and-death)).

## Examples

Make home where the bot stands:

```python
set_home(pos())
```

## See also

[`home`](home.md), [`home_dimension`](home_dimension.md), [Death and respawn](../../features/death.md).
