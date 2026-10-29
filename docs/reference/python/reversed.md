# `reversed`

```text
reversed(sequence)
```

The elements of `sequence` from last to first.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `sequence` | list, tuple, str, range or dict | | the elements; a dict gives its keys |

## Returns

An iterator over the elements back to front. Like Python's it goes through them once: loop over it with `for`, or make a list of it with [`list`](list.md) to keep it.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself; the loop over it costs as any loop does.

## Description

The elements are taken when `reversed` is called, so changing the list afterwards does not change what the iterator gives. An iterator, such as what [`enumerate`](enumerate.md) or [`zip`](zip.md) give, cannot be reversed: make a list of it first. A sorted list back to front is `sorted(xs, reverse=True)` (see [`sorted`](sorted.md)).

## Examples

```python
print(list(reversed([1, 2, 3])), "".join(reversed("abc")))   # [3, 2, 1] cba
```

Walk back the way the bot came:

```python
trail = memory.get("trail", [])
for p in reversed(trail):
    goto(p)
```

## See also

[`sorted`](sorted.md), [`list` methods](../types/list.md), [`range`](range.md).
