# `enumerate`

```text
enumerate(iterable, start=0)
```

The elements of `iterable`, each paired with its index: `(start, first)`, `(start + 1, second)`, ...

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `iterable` | list, tuple, str, dict, range or iterator | | the elements; a dict gives its keys |
| `start` | int | `0` | the index of the first element |

## Returns

An iterator of tuples `(index, element)`. Like Python's it goes through its elements once: loop over it with `for`, or make a list of it with [`list`](list.md) to keep it.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself; the loop over it costs as any loop does.

## Description

The iterator reads `iterable` as it goes, so a list that grows while it is looped over gives its new elements too, as in Python.

## Examples

```python
print(list(enumerate(["oak", "birch"], 1)))   # [(1, 'oak'), (2, 'birch')]
```

Number the chests around:

```python
for i, chest in enumerate(scan("chest", radius=16)):
    print(f"chest {i}: {container(chest)}")
```

## See also

[`zip`](zip.md), [`range`](range.md), [`for`](../../language/control-flow.md#for).
