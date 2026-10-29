# `info`

```text
info(*values, sep=" ")
```

Writes a line to the bot's log at the `info` level.

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

The line goes to the bot's log (`/antcraft bot log`), and to the owner's chat only when `scriptChatLevel` (config, default `error`) is `debug` or `info`. It takes its values as [`print`](print.md) does. For what the script did, such as a trip finished, where `debug` is for the details.

## Examples

```python
n = count("#minecraft:logs")
info(f"stored {n} logs")
```

## See also

[`debug`](debug.md), [`warn`](warn.md), [`error`](error.md), [`print`](print.md), [Output and logging](../../features/scripts.md#output-and-logging).
