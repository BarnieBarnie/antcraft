# `str` methods

The methods of a string, called on it with a dot: `"a,b".split(",")`. A string cannot be changed, so each gives a new one. They behave as in Python; the ones not listed here, such as `find` or `lstrip`, are not there, and calling one stops the script with an error. Each costs one instruction, the call itself, and is done at once. Making a string from another value is [`str`](../python/str.md); strings themselves, f-strings and `%` are on [Text](../../language/values.md#text).

## `split(sep=None, maxsplit=-1)`

The parts of the string between each `sep`, as a list of strings. Without `sep` (or with `None`) it splits at runs of whitespace and leaves out empty parts at either end. `maxsplit` is how many splits at most; less than 0 means no limit. An empty `sep` stops the script with an error.

```python
print("minecraft:oak_log".split(":"), " a  b ".split(), "a,b,c".split(",", 1))   # ['minecraft', 'oak_log'] ['a', 'b'] ['a', 'b,c']
```

## `join(iterable)`

The strings of `iterable` joined into one, with the string between each two. An element that is not a string stops the script with an error: turn numbers into text with [`str`](../python/str.md) first.

```python
print(", ".join(["coal", "iron"]), "-".join([str(n) for n in range(3)]))   # coal, iron 0-1-2
```

## `strip(chars=None)`

The string without whitespace at either end, or without any of the characters of `chars` there.

```python
print("  stone ".strip(), "--log--".strip("-"))   # stone log
```

## `startswith(prefix)` and `endswith(suffix)`

Whether the string starts with `prefix`, or ends with `suffix`. Either may be a tuple of strings, true when the string starts (or ends) with any of them. Python's `start` and `end` arguments are not there.

```python
print("oak_log".startswith("oak"), "oak_log".endswith(("_log", "_wood")))   # True True
```

## `replace(old, new, count=-1)`

The string with each `old` replaced by `new`, the first `count` of them only when `count` is 0 or more.

```python
print("oak_log".replace("oak", "birch"), "a.b.c".replace(".", "/", 1))   # birch_log a/b.c
```

## `lower()` and `upper()`

The string in lower case, or upper case.

```python
print("Stone".lower(), "stone".upper())   # stone STONE
```

## `format(*args, **kwargs)`

The string with each `{}` field filled in, as `str.format` does in Python: `{}` takes the next argument, `{0}` one by position, `{name}` one by keyword, and a format spec after a `:` sets how it looks (see [Text](../../language/values.md#text)).

```python
print("{} of {}".format(3, 64), "{item}: {n:>3}".format(item="coal", n=7))   # 3 of 64 coal:   7
```

## In a bot script

The part of an item id after its namespace:

```python
def short(item):
    return item.split(":")[-1]

say(", ".join([short(s.item) for s in inventory()]))
```

## See also

[`str`](../python/str.md), [`list` methods](list.md), [`dict` methods](dict.md), [Text](../../language/values.md#text), [Output & logging](../../language/output.md).
