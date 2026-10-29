# `float`

```text
float(x=0.0)
```

`x` as a float.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `x` | int, float, bool or str | `0.0` | the number, or text that spells one |

## Returns

A float: `float()` is `0.0`, `float(True)` is `1.0`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

Text is read as Python reads it: spaces around it are left out, a sign, digits with a point and an exponent (`"2.5"`, `"-1e3"`, `".5"`), underscores between digits (`"1_000.5"`), and `"inf"`, `"infinity"` and `"nan"` in any case. Other text, or a value of another type, stops the script with an error (see [Errors & Result](../../language/errors.md)).

## Examples

```python
print(float(3), float(" 2.5 "), float("1e3"), float("-inf"))   # 3.0 2.5 1000.0 -inf
```

Read a number from a script's arguments:

```python
speed = float(ARGS[0]) if ARGS else 1.0
```

## See also

[`int`](int.md), [`round`](round.md), [`str`](str.md), [Numbers](../../language/values.md#numbers).
