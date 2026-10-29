# `error`

```text
error(*values, sep=" ")
```

Writes a line to the bot's log at the `error` level, the highest.

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

The line goes to the bot's log (`/antcraft bot log`) and, with the default `scriptChatLevel` (config) of `error`, to the owner's chat. Runtime and compile errors are logged at this level too. It takes its values as [`print`](print.md) does.

`error` only reports: the script goes on. To end it as well, call [`stop`](stop.md) after it.

## Examples

```python
if not goto(home()):
    error("cannot get home from", pos())
    stop()
```

## See also

[`warn`](warn.md), [`stop`](stop.md), [`print`](print.md), [Output and logging](../../features/scripts.md#output-and-logging), [Errors & Result](../../language/errors.md).
