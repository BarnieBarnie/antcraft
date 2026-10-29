# `tuple`

```text
tuple(iterable=())
```

A new tuple of the elements of `iterable`.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `iterable` | list, tuple, str, dict, dict view, range or iterator | `()` | the elements; a dict gives its keys, a string its characters |

## Returns

A tuple: `tuple()` is `()`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself, however many elements there are.

## Description

A tuple cannot be changed, and can be a dict key where a list cannot. A tuple of three ints is taken wherever a position is (see [`Pos`](../types/pos.md)). `tuple` is also a type for [`isinstance`](isinstance.md).

## Examples

```python
print(tuple([1, 2, 3]), tuple("ab"), tuple())   # (1, 2, 3) ('a', 'b') ()
```

Count the blocks around by id and height, with tuples as dict keys:

```python
found = {}
for p in scan("#minecraft:logs", radius=8):
    key = tuple([block(p).id, p.y])
    found[key] = found.get(key, 0) + 1
```

## See also

[`list`](list.md), [`zip`](zip.md), [Tuples](../../language/values.md#tuples), [`Pos`](../types/pos.md).
