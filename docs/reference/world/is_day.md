# `is_day`

```text
is_day()
```

Whether it is light outside in the bot's world: day, as the game counts it.

## Returns

A bool.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

It is the game's own test, the one that says whether the sky is bright: true from about sunrise to sunset, by [`time`](time.md). A thunderstorm darkens the sky enough to make it false by day; rain alone does not. In a dimension without a day, such as the Nether or the End, it is always false.

## Examples

Wait at home for the night to pass:

```python
if not is_day() and dimension() == home_dimension():
    goto(home())
    while not is_day():
        wait(200)
```

## See also

[`time`](time.md), [`wait`](../index.md#the-bot-and-its-output).
