# `sum`

```text
sum(iterable, start=0)
```

The elements of `iterable` added up, after `start`.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `iterable` | list, tuple, dict view, range or iterator | | the elements to add |
| `start` | any but a string | `0` | what to add them to |

## Returns

`start + first + second + ...`: an int for ints, a float once a float is among them; `start` when `iterable` is empty.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself, however many elements there are.

## Description

The elements are added with `+`, so lists add up too with `start=[]`. Strings do not: a string as `start` or among numbers stops the script with an error that says to use [`"".join(...)`](../types/str.md) instead, as do elements that cannot be added. Ints are 64-bit, and a sum that does not fit stops the script with an overflow error.

## Examples

```python
print(sum([4, 2, 8]), sum([0.5, 1]), sum(range(5), 10), sum([[1], [2, 3]], []))   # 14 1.5 20 [1, 2, 3]
```

How many logs the bot carries, of any kind:

```python
logs = sum([s.count for s in inventory() if matches(s, "#minecraft:logs")])
```

## See also

[`count`](../inventory/count.md), [`len`](len.md), [`max`](max.md), [`str.join`](../types/str.md).
