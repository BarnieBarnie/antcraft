# `str`

```text
str(object="")
```

`object` as text, as `print` shows it.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `object` | any | `""` | the value to turn into text |

## Returns

A string: `str()` is `""`, `str(3)` is `"3"`, `str(None)` is `"None"`, a string is itself. A list, tuple or dict shows its elements as Python would, strings in quotes.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Description

`+` joins only strings, so `str` turns a number into text first; an f-string does the same with less to type (see [Text](../../language/values.md#text)). The string's methods are on [`str` methods](../types/str.md); `str` is also a type for [`isinstance`](isinstance.md).

## Examples

```python
print(str(7) + "!", str(2.5), str([1, "a"]))   # 7! 2.5 [1, 'a']
```

```python
memory["last"] = "deaths: " + str(deaths())
```

## See also

[`str` methods](../types/str.md), [`int`](int.md), [`float`](float.md), [`print`](../bot/print.md), [Text](../../language/values.md#text).
