# `keep_blocks`

```text
keep_blocks(blocks)
```

Keeps the blocks at `blocks` from the bot's walks: no path breaks them (#856).

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `blocks` | list of [`Pos`](../types/pos.md) or tuples | | the blocks to keep; a `Pos` in another dimension than the bot's is left out; `[]` keeps none again |

## Returns

`None`.

## Pauses

No, it is done at once.

## Cost

Nothing besides the call.

## Description

A walk, of [`goto`](goto.md) or of an action that walks to its target first (`mine`, `place`, `put`, `take`...), may break any block in its way but those with a block entity, such as a chest, and what players build with, such as fences and glass. `keep_blocks` adds the blocks it is given: a walk goes round them, or digs through other blocks, and where no way is left it fails with `unreachable`. It does not keep the script's own actions from them: `mine` still breaks a kept block.

Each call replaces the blocks the last kept. They stay kept until the script ends, stops or fails; a script resumed after a reload keeps none, so a long-running script, such as a farm, calls it again now and then. [`lib.cellfarm`](../modules/lib/cellfarm.md) keeps the blocks its spec's `kept` gives of each built cell, such as a cocoa farm's logs.

## Examples

Walk past a cocoa column without breaking its logs:

```python
keep_blocks([Pos(10, 64, 3), Pos(10, 65, 3)])
goto(Pos(14, 64, 3))
```

## See also

[`goto`](goto.md), [`placed_scaffold`](placed_scaffold.md), [`lib/cellfarm`](../modules/lib/cellfarm.md).
