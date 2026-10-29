# `int`

```text
int(x=0)
int(x, base)
```

`x` as an int: a number cut to a whole one, or text read as one.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `x` | int, float, bool or str | `0` | the number, or text that spells one |
| `base` | int | `10` | the base of the text, 2 to 36, or 0 to take it from the text's prefix (`0x`, `0o`, `0b`); only with a string `x` |

## Returns

An int: `int()` is `0`, `int(True)` is `1`, and a float loses what is after its point, towards zero (`int(-2.7)` is `-2`).

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

Text is read as Python reads it: spaces around it are left out, a sign, digits, underscores between digits (`"1_000"`), and with a `base` of 16, 8 or 2 the prefix `0x`, `0o` or `0b` too. Text with a point, such as `"2.5"`, is no int: use `int(float(text))`.

Ints are 64-bit: a number that does not fit, or a float that is infinite or NaN, stops the script with an error, as does text that is no int, a `base` with something else than a string, or a `base` out of range (see [Errors & Result](../../language/errors.md)). To round instead of cut, use [`round`](round.md).

## Examples

```python
print(int("12") + 1, int(3.9), int(-3.9), int("ff", 16), int("0b101", 0))   # 13 3 -3 255 5
```

Read a level from a script's arguments:

```python
level = int(ARGS[0]) if ARGS else 16
```

## See also

[`float`](float.md), [`round`](round.md), [`str`](str.md), [Numbers](../../language/values.md#numbers).
