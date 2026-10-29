# `debug`

```text
debug(*values, sep=" ")
```

Writes a line to the bot's log at the `debug` level, the lowest.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `*values` | any | | the values to write, each turned into a string as `str` does |
| `sep` | str or `None` | `" "` | what goes between the values; `None` is a space |

## Returns

`None`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

The line goes to the bot's log (`/antcraft bot log`), and to the owner's chat only when `scriptChatLevel` (config, default `error`) is `debug`. Actions that fail are logged at this level too, such as `goto(Pos(4, 70, -2)) -> unreachable`. It takes its values as [`print`](print.md) does.

## Examples

```python
target = find_nearest("#minecraft:logs", 16)
debug("nearest log:", target)
```

## See also

[`info`](info.md), [`warn`](warn.md), [`error`](error.md), [`print`](print.md), [Output and logging](../../features/scripts.md#output-and-logging).
