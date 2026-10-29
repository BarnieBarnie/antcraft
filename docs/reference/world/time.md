# `time`

```text
time()
```

The time of day in the bot's world, in ticks.

## Returns

An int from 0 to 23999: 0 is sunrise, 6000 noon, 12000 sunset and 18000 midnight. It goes up by 20 a second and starts again at 0 each morning, so it says nothing of which day it is.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

The time of day of the world the bot is in, as the game's clock gives it. Whether it is light enough outside to count as day is [`is_day()`](is_day.md).

## Examples

Wait for the next morning:

```python
while time() > 1000:
    wait(100)
```

## See also

[`is_day`](is_day.md), [`wait`](../index.md#the-bot-and-its-output).
