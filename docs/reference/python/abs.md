# `abs`

```text
abs(x)
```

The absolute value of a number: `x` without its sign.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `x` | int, float or bool | | the number |

## Returns

An int for an int or a bool (`abs(True)` is `1`), a float for a float.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

As in Python, but ints are 64-bit: `abs` of the smallest int, -9223372036854775808, does not fit and stops the script with an overflow error. Anything that is not a number, such as a string, stops the script with an error too (see [Errors & Result](../../language/errors.md)).

## Examples

How far the bot is from its home along each axis:

```python
d = pos() - home()
print(abs(d.x), abs(d.y), abs(d.z))
```

```python
print(abs(-3), abs(2.5), abs(-0.0))   # 3 2.5 0.0
```

## See also

[`round`](round.md), [`int`](int.md), [Numbers](../../language/values.md#numbers), [`Pos.distance`](../types/pos.md).
