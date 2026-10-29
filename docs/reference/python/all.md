# `all`

```text
all(iterable)
```

Whether every element of `iterable` is truthy.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `iterable` | list, tuple, str, dict, range or iterator | | the elements to test; a dict gives its keys |

## Returns

`True` when every element is truthy, also when there are none; `False` otherwise.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself, however many elements there are. Building the list it tests, such as with a list comprehension, costs instructions of its own.

## Description

What counts as truthy is as in Python (see [Truthiness](../../language/values.md#truthiness)). There are no generator expressions, so test a list comprehension: `all([has(i) for i in items])`. Every element is collected before any is tested, so `all` does not stop early.

## Examples

Whether the bot carries everything for a job:

```python
need = ["minecraft:ladder", "minecraft:torch", "minecraft:chest"]
if all([has(item) for item in need]):
    say("ready")
```

```python
print(all([1, "a", True]), all([1, 0]), all([]))   # True False True
```

## See also

[`any`](any.md), [`bool`](bool.md), [Control flow](../../language/control-flow.md).
