# `range`

```text
range(stop)
range(start, stop, step=1)
```

The ints from `start` up to, not including, `stop`, `step` apart.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `start` | int | `0` | the first number |
| `stop` | int | | where to stop; it is not in the range |
| `step` | int | `1` | the distance between two numbers; less than 0 counts down; 0 stops the script with an error |

The arguments are given by position only; a bool counts as an int.

## Returns

A `range`: it holds no list but gives its numbers when looped over, so `range(1000000)` takes no room. It has a length ([`len`](len.md)), can be indexed, tested with `in`, and looped over more than once. [`list`](list.md) makes a list of it.

## Pauses

No, it is done at once.

## Cost

One instruction, the call itself, however long the range; the loop over it costs as any loop does.

## Description

As in Python. `range` is also a type for [`isinstance`](isinstance.md).

## Examples

```python
print(list(range(3)), list(range(2, 10, 3)), list(range(3, 0, -1)))   # [0, 1, 2] [2, 5, 8] [3, 2, 1]
```

Wait out a minute in steps, checking for danger:

```python
for _ in range(12):
    if entities("zombie", radius=8):
        break
    wait(100)
```

## See also

[`enumerate`](enumerate.md), [`len`](len.md), [`for`](../../language/control-flow.md#for).
