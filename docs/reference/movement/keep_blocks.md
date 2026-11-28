# `keep_blocks`

```text
keep_blocks(blocks)
```

Keeps the blocks at `blocks` from the bot's walks: no path breaks them (#856) or puts scaffold in their place (#869).

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `blocks` | list of [`Pos`](../types/pos.md), tuples or boxes | | the blocks to keep: a position, or a box `(low, high)` of two positions, which keeps every block from one corner to the other (#891); a `Pos` or box (by `low`) in another dimension than the bot's is left out; `[]` keeps none again |

## Returns

`None`.

## Pauses

No, it is done at once.

## Cost

Nothing besides the call. Building a long list of positions in the script costs instructions: a box costs as little as one position, so give a large area, such as a cell's floor, as a box.

## Description

A walk, of [`goto`](goto.md) or of an action that walks to its target first (`mine`, `place`, `put`, `take`...), may break any block in its way but those with a block entity, such as a chest, and what players build with, such as fences and glass. `keep_blocks` adds the blocks it is given: a walk goes round them, or digs through other blocks, and where no way is left it fails with `unreachable`. Nor does a pillar or bridge place scaffold where a kept block is, such as in a cell's water or where a lost block of it stood. It does not keep the script's own actions from them: `mine` still breaks a kept block.

Each call replaces the blocks the last kept. They stay kept until the script ends, stops or fails, also across a reload: they are saved with the script (#906). A script resumed in another dimension, through a portal, keeps none. A bot that dies runs its script again from the top, so a long-running script, such as a farm, calls it when it starts. [`lib.cellfarm`](../modules/lib/cellfarm.md) keeps the blocks its spec's `kept` gives of each built cell, such as a cocoa farm's logs or a crop farm's soil and water, when it starts and once it built a cell. `colony.farmer` keeps each farm's soil and water every round (#1039).

## Examples

Walk past a cocoa column without breaking its logs:

```python
keep_blocks([Pos(10, 64, 3), Pos(10, 65, 3)])
goto(Pos(14, 64, 3))
```

Keep a 9 by 9 floor:

```python
keep_blocks([(Pos(0, 63, 0), Pos(8, 63, 8))])
```

## See also

[`goto`](goto.md), [`placed_scaffold`](placed_scaffold.md), [`lib/cellfarm`](../modules/lib/cellfarm.md).
