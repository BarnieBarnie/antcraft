# `isinstance`

```text
isinstance(obj, class_or_tuple)
```

Whether `obj` is of a type, or of one of a tuple of types.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `obj` | any | | the value to test |
| `class_or_tuple` | type, or a tuple of types | | `int`, `float`, `str`, `bool`, `list`, `dict`, `tuple` or `range`, or a tuple of them such as `(int, float)` |

## Returns

`True` or `False`. A bool is an int too, as in Python: `isinstance(True, int)` is `True`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

Only the eight builtin types above can be tested; anything else as `class_or_tuple`, such as a function or a tuple inside the tuple, stops the script with an error. The API's types ([`Pos`](../types/pos.md), [`Stack`](../types/stack.md), ...) are no names a script can use: test what a value has instead, and test for `None` with `is None`.

## Examples

```python
print(isinstance(3, int), isinstance(3.0, (int, float)), isinstance("3", int))   # True True False
```

Take a count or a list of items:

```python
def amounts(what):
    if isinstance(what, (int, float)):
        return [what]
    return list(what)
```

## See also

[`int`](int.md), [`str`](str.md), [`list`](list.md), [`dict`](dict.md), [Values & types](../../language/values.md).
