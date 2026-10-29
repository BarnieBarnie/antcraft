# `last_death`

```text
last_death()
```

Where the bot last died.

## Returns

A [`Pos`](../types/pos.md) with the dimension it died in, or `None` if the bot never died. It is saved with the bot and kept across respawns.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

A dead bot drops what it carried where it died and respawns at its home, in its home's dimension. To fetch its items, a script walks back to `last_death()`; when the bot died in another dimension, [`goto`](../index.md#movement) fails with `wrong_dimension` until it has travelled there. Compare `last_death().dimension` with [`dimension()`](../index.md#movement) first.

## Examples

```python
p = last_death()
if p is not None and p.dimension == dimension():
    if goto(p, 2):
        pick_up()
```

## See also

[`deaths`](deaths.md), [`pick_up`](../index.md#inventory), [Death and respawn](../../features/death.md).
