# `mine`

```text
mine(pos)
```

Walks into reach of a block and breaks it with the best tool the bot carries.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | the block to break |

## Returns

A [`Result`](../types/result.md), ok once the block is broken.

## Pauses

Yes: it walks, as [`goto`](../movement/goto.md) does, to where the bot sees the block within 4 blocks of its eyes, then digs as long as the block takes to break with the tool.

## Cost

One instruction, the call itself. The walk plans its path as `goto` does.

## Description

The bot picks the best tool it carries for the block and holds it; with none, it digs by hand where a hand makes the block drop something. The drops go straight into the inventory; what does not fit lies on the ground.

Mining crops harvests them: `mine` on ripe wheat (`block(p).state["age"] == 7`) gives wheat and seeds.

## Examples

```python
log = find_nearest("#minecraft:logs")
if log is not None:
    r = mine(log)
    if not r:
        warn("could not mine", log, r.reason)
```

## Fails with

| Reason | When |
|---|---|
| `target_gone` | air or a fluid is there, or the block went while the bot dug |
| `protected` | the block may not be changed (outside the build height or border, spawn protection), or cannot be broken, such as bedrock |
| `no_tool` | no carried tool makes the block drop anything, such as stone without a pickaxe |
| `out_of_reach` | the bot could not walk into reach |
| `no_space` | the block is mined, but drops that did not fit lie on the ground |
| `not_loaded` | the block is in a chunk that is not loaded |
| `wrong_dimension` | `pos` is in another dimension than the bot's |
| `timeout` | the walk got stuck |

## See also

[`place`](place.md), [`use`](use.md), [`find_nearest`](../world/find_nearest.md), [`pick_up`](../inventory/pick_up.md).
