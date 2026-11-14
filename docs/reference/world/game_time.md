# `game_time`

```text
game_time()
```

The ticks the bot's world has run.

## Returns

An int that goes up by 20 a second, 1 a game tick, and never starts again, unlike [`time()`](time.md). It stands still while the server is stopped or the game paused.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

The game time of the world the bot is in, as the game counts it. The difference of two readings is how long something took in ticks, over any number of days.

## Examples

Time a felling:

```python
import lib.trees

start = game_time()
lib.trees.fell(lib.trees.next_tree())
say(f"felled in {game_time() - start} ticks")
```

## See also

[`time`](time.md), [`wait`](../bot/wait.md).
