# `min`

```text
min(iterable, key=None, default=None)
min(a, b, *more, key=None)
```

The smallest element of `iterable`, or the smallest of several arguments.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `iterable` | list, tuple, str, dict, range or iterator | | the elements, when it is the one positional argument; a dict gives its keys |
| `a`, `b`, `*more` | any | | the values, when there are two or more positional arguments |
| `key` | function | `None` | a function of one argument; the elements are compared by what it gives for each. By keyword only |
| `default` | any | `None` | what to give when `iterable` is empty; only with one positional argument. By keyword only |

## Returns

The element that is smallest, or whose key is; the first of equal ones. `default` when `iterable` is empty.

## Pauses

Without `key`, no: it is done at once. With `key`, the key function runs as script code, so it can use up the tick's budget and the script goes on the next tick (see [Budget & pausing](../../language/budget.md)).

## Cost

One instruction, the call itself; with `key`, also the instructions of each call of `key` and a few per element to collect them.

## Description

Elements are compared with `<` as in Python: numbers with numbers, strings with strings, lists and tuples element by element. Values that cannot be compared, such as a string and an int, stop the script with an error, as does an empty `iterable` without `default`, or `default` with several positional arguments. The key function is one written with `def`, a `lambda` or a builtin such as `len` (see [Functions as values](../../language/functions.md#functions-as-values)).

## Examples

```python
print(min(4, 2, 8), min([4, 2, 8]), min([], default=0))   # 2 2 0
print(min(["oak", "spruce", "fir"], key=len))           # oak
```

The nearest of the chests around:

```python
def distance_to_me(p):
    return pos().distance(p)

nearest = min(scan("chest", radius=16), key=distance_to_me, default=None)
if nearest is not None:
    goto(nearest, near=2)
```

## See also

[`max`](max.md), [`sorted`](sorted.md), [`find_nearest`](../world/find_nearest.md), [`Pos`](../types/pos.md).
