# `stop`

```text
stop()
```

Ends the script.

## Returns

Nothing: no line after the call runs.

## Pauses

The script ends at the call, also from inside a function or an imported module.

## Cost

One instruction, the call itself.

## Description

The script ends as one stopped with `/antcraft bot stop`: the bot goes back to `idle.py` (walk home, then wait), and the jobs it took off its colony's job board go back to the board, open again. Reaching the end of the script ends it too; `stop` is for ending it early.

To log why before it ends, write an [`error`](error.md) or [`print`](print.md) line first.

## Examples

```python
if not has("#minecraft:axes"):
    say("no axe, stopping")
    stop()
```

## See also

[`wait`](wait.md), [`error`](error.md), [Scripts in the game](../../features/scripts.md), [Idle, home and death](../../features/death.md#idle-home-and-death).
