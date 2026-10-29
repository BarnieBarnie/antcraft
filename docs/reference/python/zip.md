# `zip`

```text
zip(*iterables)
```

The elements of several iterables side by side: a tuple of their first elements, then of their second, ...

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `*iterables` | lists, tuples, strs, dicts, ranges or iterators | | the iterables, any number; a dict gives its keys |

It takes no keyword arguments, so there is no `strict=`.

## Returns

An iterator of tuples, as long as the shortest iterable; `zip()` gives none. Like Python's it goes through them once: loop over it with `for`, or make a list or dict of it to keep it.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself; the loop over it costs as any loop does.

## Description

As in Python. [`dict`](dict.md) of a `zip` of keys and values makes a dict.

## Examples

```python
print(list(zip(["coal", "iron"], [3, 5])))      # [('coal', 3), ('iron', 5)]
print(dict(zip("ab", range(10))))               # {'a': 0, 'b': 1}
```

Take a list of items, each with its own count:

```python
items = ["minecraft:ladder", "minecraft:torch"]
needs = [16, 8]
for item, n in zip(items, needs):
    if count(item) < n:
        warn(f"short of {item}")
```

## See also

[`enumerate`](enumerate.md), [`dict`](dict.md), [`for`](../../language/control-flow.md#for).
