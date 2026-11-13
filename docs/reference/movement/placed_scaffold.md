# `placed_scaffold`

```text
placed_scaffold()
```

Where the script's walks placed scaffold blocks since it last asked, and forgets them.

## Returns

A list of [`Pos`](../types/pos.md), each with its `dimension`, oldest first: every block the bot placed to pillar up or bridge over a gap while it walked for this script, for [`goto`](goto.md) and for the actions that walk to their target first (`mine`, `place`, `put`, `take`...), since the script started or last called `placed_scaffold()`. A second call right after gives `[]`.

## Pauses

No, it is done at once.

## Cost

10 instructions besides the call.

## Description

The positions belong to the script run: a new script starts with none. They are saved with the bot, so a script resumed after a reload still gets what it placed before. The bot keeps the last 4096 for a script that never asks.

Only blocks that have not changed since the bot placed them are given: one mined, replaced or changed in any way since, by the bot, another bot, a player, a piston or anything else, is left out, even where the same block is back in its place, so a script takes back only the bot's own. [`lib.trees.fell_whole`](../modules/lib/trees.md) takes back what it gives with `lib.trees.take_back()`.

## Examples

Walk somewhere, then take back the blocks the walk put up, highest first:

```python
placed_scaffold()  # forget what earlier walks placed
goto(Pos(100, 80, 100))
for p in sorted(placed_scaffold(), key=lambda p: -p.y):
    mine(p)
```

## See also

[`goto`](goto.md), [`block`](../world/block.md), [`lib.trees.fell_whole`](../modules/lib/trees.md).
