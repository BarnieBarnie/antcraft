# `list` methods

The methods of a list, called on it with a dot: `xs.append(3)`. They change the list in place and, but for `pop` and `index`, give `None`, as in Python. The ones not listed here, such as `count`, `clear` or `reverse`, are not there, and calling one stops the script with an error. Each costs one instruction, the call itself, and is done at once, but for `sort` with a `key`. Making a list from another value is [`list`](../python/list.md); lists themselves, indexing and slicing are on [Lists](../../language/values.md#lists).

## `append(object)`

Adds `object` at the end.

```python
xs = [1, 2]
xs.append(3)
print(xs)   # [1, 2, 3]
```

## `extend(iterable)`

Adds each element of `iterable` at the end.

```python
xs = [1]
xs.extend(range(2, 4))
print(xs)   # [1, 2, 3]
```

## `pop(index=-1)`

Takes the element at `index` out of the list and gives it: the last one without `index`, the first with `pop(0)`. A negative `index` counts from the end. An empty list, or an `index` out of range, stops the script with an error.

```python
xs = [1, 2, 3]
print(xs.pop(), xs.pop(0), xs)   # 3 1 [2]
```

## `insert(index, object)`

Puts `object` in before the element at `index`; an `index` past either end puts it at that end, a negative one counts from the end.

```python
xs = ["b", "c"]
xs.insert(0, "a")
print(xs)   # ['a', 'b', 'c']
```

## `remove(value)`

Takes the first element equal to `value` out of the list. Without one, it stops the script with an error: test with `value in xs` first.

```python
xs = ["dirt", "stone", "dirt"]
xs.remove("dirt")
print(xs)   # ['stone', 'dirt']
```

## `index(value, start=0, stop=len)`

The index of the first element equal to `value`, looking from `start` up to, not including, `stop`; negative ones count from the end. Without one, it stops the script with an error.

```python
print(["a", "b", "c", "b"].index("b"), ["a", "b", "c", "b"].index("b", 2))   # 1 3
```

## `sort(key=None, reverse=False)`

Sorts the list in place, smallest first, as [`sorted`](../python/sorted.md) sorts: stable, by what `key` gives for each element if given, largest first with `reverse`. Both are by keyword only. With `key`, the key function runs as script code: its instructions count, and it can use up the tick's budget so the script goes on the next tick (see [Budget & pausing](../../language/budget.md)).

```python
xs = ["spruce", "oak", "fir"]
xs.sort()
print(xs)   # ['fir', 'oak', 'spruce']
xs.sort(key=len, reverse=True)
print(xs)   # ['spruce', 'fir', 'oak']
```

## In a bot script

A queue of places to visit, kept in `memory`:

```python
todo = memory.setdefault("todo", [])
if not todo:
    todo.extend(scan("chest", radius=16))
while todo:
    goto(todo.pop(0), near=2)
```

## See also

[`list`](../python/list.md), [`sorted`](../python/sorted.md), [`str` methods](str.md), [`dict` methods](dict.md), [Lists](../../language/values.md#lists).
