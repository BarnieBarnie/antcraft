# `any`

```text
any(iterable)
```

Whether any element of `iterable` is truthy.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `iterable` | list, tuple, str, dict, range or iterator | | the elements to test; a dict gives its keys |

## Returns

`True` when at least one element is truthy; `False` otherwise, also when there are none.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself, however many elements there are. Building the list it tests, such as with a list comprehension, costs instructions of its own.

## Description

What counts as truthy is as in Python (see [Truthiness](../../language/values.md#truthiness)). A generator expression works too, `any(b.is_fluid for b in blocks)`, but every element is collected before any is tested, so `any` does not stop early (see [Generator expressions](../../language/functions.md#generator-expressions)).

## Examples

Whether a zombie is near:

```python
if any([e.type == "minecraft:zombie" for e in entities(radius=8)]):
    warn("zombie close by")
```

```python
print(any([0, "", None]), any([0, 3]), any([]))   # False True False
```

## See also

[`all`](all.md), [`bool`](bool.md), [`entities`](../world/entities.md).
