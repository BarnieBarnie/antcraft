# `matches`

```text
matches(item, selector)
```

Whether an item is one a selector selects.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | str or [`Stack`](../types/stack.md) | | an item id, such as `"birch_log"`, or a stack, such as one from [`container`](../crafting/container.md) |
| `selector` | [selector](../types/selectors.md) | | what it is tested against, such as `"#minecraft:logs"` |

## Returns

A bool.

## Pauses

No, it is done at once.

## Cost

15 instructions besides the call.

## Description

It tells a script which of the stacks it reads, in a chest or the bot's inventory, are the kind it wants, by the same rules the actions use for their `item`. It does not look at the bot or the world.

A malformed item id (`'Oak Log' is not an item id: ...`), an item the game does not know, an `item` that is neither a str nor a `Stack`, or a bad selector stops the script with an error.

## Examples

```python
chest = memory["chest"]
for s in container(chest) or []:
    if matches(s, "#minecraft:logs"):
        take(chest, s.item)
```

## See also

[selectors](../types/selectors.md), [`count`](count.md), [`container`](../crafting/container.md).
