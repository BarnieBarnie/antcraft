# `lib/result`

The dict every doer of the library gives. A module of the mod's default library: `from lib.result import result` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/result.py`, and the bottom layer of the library: it imports nothing, and every other module may import it (see [Writing library functions](../../index.md#writing-library-functions)).

## `result(reason=None, at=None, missing=None)`

- **Returns** a dict: `ok` (`True` when `reason` is `None`), `reason` (`None`, or why it stopped, as an action's [`Result`](../../types/result.md) gives), `at` (where it stopped, or `None`) and `missing` (a dict of item to how many it lacks; `{}` when `None`).
- **Pauses** no.

```python
from lib.result import result

def fetch_torches(chest):
    if count("torch") >= 8:
        return result()
    take(chest, "torch", 8 - count("torch"))
    if count("torch") < 8:
        return result("missing_ingredients", chest, {"minecraft:torch": 8 - count("torch")})
    return result()

r = fetch_torches(Pos(10, 64, 10))
if not r["ok"]:
    print("short:", r["missing"])
```

[`lib/shaft`](shaft.md) still has it too, so `from lib.shaft import result` keeps working.

## See also

[`Result`](../../types/result.md), [`lib/shaft`](shaft.md), [Writing library functions](../../index.md#writing-library-functions).
