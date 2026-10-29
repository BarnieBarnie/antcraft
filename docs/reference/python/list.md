# `list`

```text
list(iterable=())
```

A new list of the elements of `iterable`.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `iterable` | list, tuple, str, dict, dict view, range or iterator | `()` | the elements; a dict gives its keys, a string its characters |

## Returns

A new list: `list()` is `[]`. A list given is copied, so changing the copy leaves it alone.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself, however many elements there are.

## Description

The usual way to keep what an iterator gives ([`enumerate`](enumerate.md), [`zip`](zip.md), [`reversed`](reversed.md)) or a dict's keys, values or items as a list. The list's methods are on [`list` methods](../types/list.md); `list` is also a type for [`isinstance`](isinstance.md).

## Examples

```python
print(list(range(3)), list("ab"), list({"x": 1, "y": 2}))   # [0, 1, 2] ['a', 'b'] ['x', 'y']
```

Work on a copy of what is remembered:

```python
todo = list(memory.get("todo", []))
while todo:
    goto(todo.pop(0))
memory["todo"] = todo
```

## See also

[`list` methods](../types/list.md), [`tuple`](tuple.md), [`sorted`](sorted.md), [Lists](../../language/values.md#lists).
