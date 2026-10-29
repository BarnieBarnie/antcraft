# `dict` methods

The methods of a dict, called on it with a dot: `d.get("coal", 0)`. They behave as in Python; the ones not listed here, such as `update`, `clear` or `copy`, are not there, and calling one stops the script with an error (`dict(d)` makes a copy, see [`dict`](../python/dict.md)). Each costs one instruction, the call itself, and is done at once. Dicts themselves, `d[key]` and `in` are on [Dicts](../../language/values.md#dicts); [`memory`](memory.md) and `colony_memory` are dicts too.

## `get(key, default=None)`

The value of `key`, or `default` when the dict has no such key. `d[key]` instead stops the script with an error for a missing key.

```python
d = {"coal": 3}
print(d.get("coal"), d.get("iron"), d.get("iron", 0))   # 3 None 0
```

## `keys()`, `values()` and `items()`

Views of the dict's keys, values, or `(key, value)` tuples, in the order the keys were put in. A view follows the dict: it shows what is in it when it is read. Loop over it, test with `in`, count with [`len`](../python/len.md), or make a list of it with [`list`](../python/list.md).

```python
d = {"coal": 3, "iron": 1}
print(list(d.keys()), list(d.values()), list(d.items()))   # ['coal', 'iron'] [3, 1] [('coal', 3), ('iron', 1)]
for item, n in d.items():
    print(item, n)
```

## `pop(key, default)`

Takes `key` out of the dict and gives its value; `default` when the dict has no such key. Without `default`, a missing key stops the script with an error. It is the way to take a key out, as there is no `del`.

```python
d = {"coal": 3, "iron": 1}
print(d.pop("coal"), d.pop("gold", 0), d)   # 3 0 {'iron': 1}
```

## `setdefault(key, default=None)`

The value of `key`; when the dict has no such key, it first puts `key` in with `default` as its value.

```python
d = {}
d.setdefault("logs", []).append("oak")
print(d)   # {'logs': ['oak']}
```

## In a bot script

Forget the shaft [`lib/shaft`](../modules/lib/shaft.md) keeps, so the next `dig_shaft` picks a new site:

```python
memory.pop("shaft", None)
```

## See also

[`dict`](../python/dict.md), [`memory`](memory.md), [`list` methods](list.md), [`str` methods](str.md), [Dicts](../../language/values.md#dicts).
