# `max_health`

```text
max_health()
```

The most health the bot can have, a float.

## Returns

`20.0` for a bot: ten hearts.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

Compare [`health()`](health.md) with it rather than with `20`, so a script keeps working if a bot's maximum ever changes.

## Examples

```python
print("health", health(), "of", max_health())
```

## See also

[`health`](health.md).
