# `dict`

```text
dict(**names)
dict(source, **names)
```

A new dict: empty, a copy of `source`, or made of pairs; keyword arguments add entries with string keys.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `source` | dict, or an iterable of pairs | | the entries to start with: a dict's, or each pair's key and value. It is given by position only |
| `**names` | any | | more entries, each keyword its key: `dict(n=3)` is `{"n": 3}`. They come after those of `source` and replace one with the same key |

## Returns

A new dict, keeping the order its keys were first put in.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

Each element of a `source` that is not a dict must have two elements, such as a tuple `(key, value)` or a list of two; one of another length, or more than one positional argument, stops the script with an error. Keys must be hashable: numbers, strings, tuples, [`Pos`](../types/pos.md), not lists or dicts. The dict's methods are on [`dict` methods](../types/dict.md); `dict` is also a type for [`isinstance`](isinstance.md).

## Examples

```python
counts = dict(coal=3, iron=1)
print(counts)                                   # {'coal': 3, 'iron': 1}
print(dict([("a", 1), ("b", 2)], b=5))          # {'a': 1, 'b': 5}
print(dict(zip(["x", "y"], [4, 7])))            # {'x': 4, 'y': 7}
```

Count the stacks the bot carries by item:

```python
carried = dict()
for s in inventory():
    carried[s.item] = carried.get(s.item, 0) + s.count
```

## See also

[`dict` methods](../types/dict.md), [`zip`](zip.md), [`list`](list.md), [Dicts](../../language/values.md#dicts), [`memory`](../types/memory.md).
