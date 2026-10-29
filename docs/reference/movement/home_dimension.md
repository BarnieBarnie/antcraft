# `home_dimension`

```text
home_dimension()
```

The id of the dimension of the bot's home, such as `"minecraft:overworld"`.

## Returns

A string, the same as `home().dimension`.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself.

## Examples

```python
if dimension() != home_dimension():
    say("away from home, in " + dimension())
```

## See also

[`dimension`](dimension.md), [`home`](home.md), [`set_home`](set_home.md), [`travel`](travel.md).
