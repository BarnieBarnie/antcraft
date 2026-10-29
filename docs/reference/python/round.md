# `round`

```text
round(number, ndigits=None)
```

`number` rounded to a whole number, or to `ndigits` digits after the point.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `number` | int, float or bool | | the number to round |
| `ndigits` | int or `None` | `None` | how many digits after the point to keep; less than 0 rounds to tens, hundreds, ... |

## Returns

Without `ndigits` (or with `None`), an int. With `ndigits`, a number of the same type as `number`: a float for a float, an int for an int.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

A number halfway between two is rounded to the even one, as in Python: `round(2.5)` is `2`, `round(3.5)` is `4`. A float is rounded as the binary number it is, so `round(2.675, 2)` is `2.67`. Rounding an infinite or NaN float to an int, or anything that is not a number, stops the script with an error. To cut a float to an int instead, use [`int`](int.md); to show it with so many digits, an f-string such as `f"{x:.2f}"` (see [Text](../../language/values.md#text)).

## Examples

```python
print(round(2.5), round(3.5), round(2.675, 2), round(1234, -2))   # 2 4 2.67 1200
```

How far it is home, in whole blocks:

```python
say(f"{round(pos().distance(home()))} blocks from home")
```

## See also

[`int`](int.md), [`abs`](abs.md), [`float`](float.md), [Numbers](../../language/values.md#numbers).
