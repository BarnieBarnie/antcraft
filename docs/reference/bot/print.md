# `print`

```text
print(*values, sep=" ")
```

Writes a line to the bot's log and to its owner's chat.

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

The line goes to the bot's log (`/antcraft bot log`) and always to the owner's chat, whatever `scriptChatLevel` (config) says: `print` is meant for the player. [`debug`](debug.md), [`info`](info.md), [`warn`](warn.md) and [`error`](error.md) write at a level instead, and go to the chat only from the chat level up.

The log keeps the bot's last 200 lines and is saved with the bot. Unlike Python's, `print` takes no `end`, `file` or `flush`: any keyword but `sep`, or a `sep` that is neither a string nor `None`, stops the script with an error. `print()` writes an empty line.

## Examples

```python
n = count("#minecraft:logs")
print("carrying", n, "logs")
print("a", "b", "c", sep=", ")
```

## See also

[`say`](say.md), [`debug`](debug.md), [`info`](info.md), [`warn`](warn.md), [`error`](error.md), [Output and logging](../../features/scripts.md#output-and-logging), [Output & logging](../../language/output.md).
