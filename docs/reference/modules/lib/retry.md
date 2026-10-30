# `lib/retry`

Trying an action again when it failed for a passing reason, such as a mob in the way. A module of the mod's default library: `import lib.retry`, then `lib.retry.retry(place, (p, "ladder", "north"))` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/retry.py`; read it to see how. [`lib/shaft`](shaft.md) places its ladders, trapdoor and torches with it.

## `retry(action, args, tries=5, again=None, delay=4)`

Calls `action` with the values of the tuple `args` as its arguments until it succeeds, fails with a reason that is not in `again`, or it was called `tries` times. Waits `delay` ticks between tries, never after a success or the last try. `action` is any function that gives a [`Result`](../../types/result.md): an action such as `place` (a function is a value, see [Functions](../../../language/functions.md#functions-as-values)) or one of your own.

- **`again`**: the reasons to try again; `None` means `AGAIN`.
- **Returns** the last result: truthy on success, else its `reason` says why it stopped.
- **Pauses** yes, while the action runs and between tries.

Pass `()` for an action without arguments and `(x,)` for one with one.

```python
import lib.retry

r = lib.retry.retry(place, (pos().up(2), "torch"), tries=3)
if not r:
    print("no torch:", r.reason)
```

## `AGAIN`

The passing reasons: `("interrupted", "timeout", "blocked")`. A reflex took over, the action was slow, or something stood in the way for a moment.

## See also

[`Result`](../../types/result.md), [`wait`](../../bot/wait.md), [`lib/shaft`](shaft.md), [Modules & import](../../../language/modules.md#the-mods-library).
