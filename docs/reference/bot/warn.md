# `warn`

```text
warn(*values, sep=" ")
```

Writes a line to the bot's log at the `warn` level.

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

The line goes to the bot's log (`/antcraft bot log`), and to the owner's chat when `scriptChatLevel` (config, default `error`) is `warn` or lower. It takes its values as [`print`](print.md) does. For something wrong the script gets past, such as a chest that is full.

## Examples

```python
if free_slots() == 0:
    warn("inventory full, dropping cobblestone")
    drop("cobblestone")
```

## See also

[`debug`](debug.md), [`info`](info.md), [`error`](error.md), [`print`](print.md), [Output and logging](../../features/scripts.md#output-and-logging).
