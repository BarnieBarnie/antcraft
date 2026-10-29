# `say`

```text
say(text)
```

Shows text above the bot and in its owner's chat.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `text` | any | | what to say; a value that is no string is turned into one as `str` does |

## Returns

`None`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

The text floats above the bot for five seconds; a new `say` replaces it. It also goes to the owner's chat as `<name> text`, whatever the chat level, and to the bot's log as a `print` line.

For a message only in the chat and the log, use [`print`](print.md), which also takes several values.

## Examples

```python
if not has("#minecraft:pickaxes"):
    say("I need a pickaxe")
```

## See also

[`print`](print.md), [`info`](info.md), [Output and logging](../../features/scripts.md#output-and-logging).
