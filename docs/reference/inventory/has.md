# `has`

```text
has(item, n=1)
```

Whether the bot carries at least `n` items that a selector selects.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | [selector](../types/selectors.md) | | the items to count |
| `n` | int | `1` | how many it must carry at least |

## Returns

A bool: `count(item) >= n`.

## Pauses

No, it is done at once.

## Cost

20 instructions besides the call.

## Description

It counts as [`count`](count.md) does, the held stack included. A malformed selector, or a name or tag the game does not know, stops the script with an error.

## Examples

```python
if has("iron_ingot", 3) and has("stick", 2):
    craft("iron_pickaxe")
```

## See also

[`count`](count.md), [`inventory`](inventory.md), [selectors](../types/selectors.md).
