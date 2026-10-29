# `bool`

```text
bool(x=False)
```

`x` as a bool: whether it is truthy.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `x` | any | `False` | the value to test |

## Returns

`True` or `False`. `bool()` is `False`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

Falsy are `False`, `None`, `0`, `0.0`, the empty string, list, tuple, dict and range; everything else is truthy, as in Python (see [Truthiness](../../language/values.md#truthiness)). A [`Result`](../types/result.md) is truthy when its action worked, so `bool(goto(p))` says whether the bot got there. `bool` is also a type for [`isinstance`](isinstance.md).

## Examples

```python
print(bool(0), bool(""), bool([0]), bool("no"))   # False False True True
```

Keep whether a walk worked:

```python
arrived = bool(goto(home()))
memory["arrived"] = arrived
```

## See also

[`any`](any.md), [`all`](all.md), [`isinstance`](isinstance.md), [True, False and None](../../language/values.md#true-false-and-none).
