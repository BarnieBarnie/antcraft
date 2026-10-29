# `home`

```text
home()
```

The bot's home: where it was first spawned, until [`set_home`](set_home.md) changes it.

## Returns

A [`Pos`](../types/pos.md) with the `dimension` of the home, which need not be the bot's.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

An idle bot walks home and waits there, and a bot that dies comes back at its home (see [Idle, home and death](../../features/death.md#idle-home-and-death)). As the home carries its dimension, `goto(home())` from another dimension fails with `wrong_dimension`: compare [`dimension`](dimension.md) with [`home_dimension`](home_dimension.md) first, and [`travel`](travel.md) if they differ. Positions compare by their coordinates alone, so `pos() == home()` is true at the home's coordinates in any dimension.

## Examples

```python
if dimension() == home_dimension():
    goto(home())
```

## See also

[`set_home`](set_home.md), [`home_dimension`](home_dimension.md), [`goto`](goto.md), [Death and respawn](../../features/death.md).
