# `sorted`

```text
sorted(iterable, key=None, reverse=False)
```

A new list of the elements of `iterable`, smallest first.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `iterable` | list, tuple, str, dict, dict view, range or iterator | | the elements; a dict gives its keys |
| `key` | function | `None` | a function of one argument; the elements are sorted by what it gives for each. By keyword only |
| `reverse` | bool | `False` | largest first. By keyword only |

## Returns

A new list; `iterable` is left as it was. To sort a list in place, use [`list.sort`](../types/list.md).

## Pauses

Without `key`, no: it is done at once. With `key`, the key function runs as script code, so it can use up the tick's budget and the script goes on the next tick (see [Budget & pausing](../../language/budget.md)).

## Cost

One instruction, the call itself; with `key`, also the instructions of each call of `key` and a few per element to collect them.

## Description

The sort is stable, as in Python: elements that compare equal keep their order, also with `reverse`. Elements are compared with `<`: numbers with numbers, strings with strings, lists and tuples element by element; values that cannot be compared, such as a string and an int, stop the script with an error. The key function is one written with `def`, a `lambda` or a builtin such as `len` (see [Functions as values](../../language/functions.md#functions-as-values)).

## Examples

```python
print(sorted([3, 1, 2]), sorted([3, 1, 2], reverse=True))   # [1, 2, 3] [3, 2, 1]
print(sorted(["oak", "spruce", "fir"], key=len))            # ['oak', 'fir', 'spruce']
```

Visit the chests around, nearest first:

```python
def distance_to_me(p):
    return pos().distance(p)

for chest in sorted(scan("chest", radius=16), key=distance_to_me):
    goto(chest, near=2)
```

## See also

[`list.sort`](../types/list.md), [`min`](min.md), [`max`](max.md), [`reversed`](reversed.md), [Functions as values](../../language/functions.md#functions-as-values).
