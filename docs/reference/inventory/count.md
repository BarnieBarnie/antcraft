# `count`

```text
count(item)
```

How many items the bot carries that a selector selects.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | [selector](../types/selectors.md) | | the items to count, such as `"cobblestone"` or `"#minecraft:logs"` |

## Returns

An int: the counts of the matching stacks added up, 0 when the bot carries none.

## Pauses

No, it is done at once.

## Cost

20 instructions besides the call.

## Description

It counts the stack in the bot's hand and every inventory slot. A malformed selector, or a name or tag the game does not know, stops the script with an error.

## Examples

```python
if count("#minecraft:logs") < 4:
    say("need more logs")
```

## See also

[`has`](has.md), [`inventory`](inventory.md), [`matches`](matches.md), [selectors](../types/selectors.md).
