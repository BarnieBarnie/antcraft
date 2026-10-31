# `goto`

```text
goto(pos, near=0, keep=None)
```

Walks the bot to `pos`, or to within `near` blocks of it, digging, pillaring and bridging where the pathfinder needs to.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `pos` | [`Pos`](../types/pos.md) or tuple | | where to go |
| `near` | int | `0` | how close is close enough, in blocks along x, y and z added up; less than 0 stops the script with an error |
| `keep` | item id, `#tag` or list of them | `None` | items the walk may not pillar or bridge with, such as blocks just gathered for a recipe |

## Returns

A [`Result`](../types/result.md), ok once the bot stands within `near` blocks of `pos`. Its `value` is `None`.

## Pauses

Yes, until the bot gets there or gives up.

## Cost

One instruction, the call itself. Planning the way draws on the server's pathfinding budget of 2000 nodes a tick, which every bot's walking shares, not on the script's budget.

## Description

The bot plans its way as it walks, and digs through, pillars up and bridges over what is in the way, with what it carries; what it digs goes into its inventory. It pillars and bridges with the blocks of the item tag `antcraft:scaffold_blocks` it carries, but none that `keep` selects: the pathfinder plans with the rest only, so with nothing else to build with it digs its way or fails with `unreachable`. Only `goto` takes `keep`: the actions that walk to their target first (`mine`, `put`, `take`...) take short walks, so a script that must keep its blocks can `goto` near the target first. It never walks into a nether portal or digs at its frame: going through a portal is [`travel`](travel.md).

Before each stretch of 48 blocks, `goto` loads the chunks the straight line towards `pos` crosses, and those within three of them, and waits for them to load (see [Chunk loading](../../features/scripts.md#chunk-loading)); chunks never generated are generated, as for a player walking there. If they are not loaded within a minute, or with the config option `botsLoadChunks` off one is not loaded, it fails with `not_loaded`.

If the bot gets no closer to `pos` for two minutes (2400 ticks), it gives up with `timeout`; a way round a lake that leads away for a while is not cut short.

A `pos` whose `dimension` is not the bot's, such as `home()` in another dimension than the home's, fails at once with `wrong_dimension`; a tuple, or a `Pos` without a dimension, is in the bot's dimension (see [Dimension](../types/pos.md#dimension)). Whether a walk would get there, without walking it, is [`reachable`](reachable.md).

## Examples

Walk to the nearest crafting table:

```python
table = find_nearest("crafting_table")
if table is not None:
    r = goto(table, near=2)
    if not r:
        say("cannot get to the table: " + r.reason)
```

Climb out of a pit without building with the cobblestone mined for a stone axe:

```python
r = goto(home(), keep="cobblestone")
```

## Fails with

| Reason | When |
|---|---|
| `unreachable` | the pathfinder finds no way there |
| `not_loaded` | a chunk on the way did not load within a minute, or is not loaded and `botsLoadChunks` is off |
| `timeout` | the bot got no closer for 2400 ticks |
| `wrong_dimension` | `pos` is in another dimension than the bot |
| `interrupted` | a [reflex](../../features/bots.md#reflexes) took over |

## See also

[`reachable`](reachable.md), [`travel`](travel.md), [`pos`](pos.md), [`home`](home.md), [`Result` and reason codes](../types/result.md#reason-codes), [Chunk loading](../../features/scripts.md#chunk-loading).
