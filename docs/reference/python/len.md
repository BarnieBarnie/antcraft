# `len`

```text
len(obj)
```

The number of elements of a string, list, tuple, dict or range.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `obj` | str, list, tuple, dict, dict view or range | | what to count |

## Returns

An int: a string's characters, a list's or tuple's elements, a dict's keys, a range's numbers.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

An iterator, such as what [`enumerate`](enumerate.md), [`zip`](zip.md) or [`reversed`](reversed.md) give, has no length: make a list of it first. A value of another type, such as an int or a `Pos`, stops the script with an error.

## Examples

```python
print(len("oak"), len([1, 2, 3]), len({"a": 1}), len(range(2, 10, 3)))   # 3 3 1 3
```

How many logs are within reach:

```python
logs = scan("#minecraft:logs", radius=16)
say(f"{len(logs)} logs around")
```

## See also

[`range`](range.md), [`list`](list.md), [`free_slots`](../inventory/free_slots.md), [Values & types](../../language/values.md).
