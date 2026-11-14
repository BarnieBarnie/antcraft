# `lib/project`

Projects: a list of steps that survives a reload, a death or a stop and rerun, and goes on at the step it was on. A module of the mod's default library: `import lib.project`, then `lib.project.run("house", steps)` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/project.py`, a kit over [`lib/retry`](retry.md), [`lib/borrow`](borrow.md) and [`lib/result`](result.md) (see [The script library](../../../design/library.md#layers)).

What a project has done is kept in [`memory`](../../types/memory.md)`["projects"][name]`, so running the same script again skips the steps it has done. Make each step safe to run twice: a step stopped halfway is run again from its start.

```python
import lib.project
from lib.project import Step
import lib.get

steps = [
    Step("tools", lib.get.ensure_tool, ("pickaxe", 1, "stone"), tries=3),
    Step("torches", lib.get.get, ("torch", 16), optional=True),
    Step("walls", build, ("my:walls", Pos(10, 64, 10))),
]
r = lib.project.run("house", steps)
if not r["ok"]:
    say("house stuck at " + r["at"] + ": " + r["reason"])
```

## `Step(name, do, args=(), tries=1, optional=False)`

One step of a project, a [class](../../../language/classes.md) instance.

- **`name`**: the step's name, a string; a project's steps have names of their own, as what is done is kept by name.
- **`do`**: a function, called as `do(*args)`. It gives a [`Result`](../../types/result.md) (an action such as `build`), a [`result()`](result.md) dict (a library doer such as `lib.get.get`), or `None`, which counts as done.
- **`tries`**: how many times `do` is called while it fails with a passing reason (see [`retry`](retry.md)).
- **`optional`**: when `True`, a failure is noted in `failed` and the project goes on; else the project stops there.

## `run(name, steps)`

Does `steps`, a list of `Step`, in order, skipping the steps of the project `name` that are done. Before each step it starts it [`say`](../../bot/say.md)s one line, such as `house 3/3: walls`. At the end, also when a step failed, it gives back what the bot [borrowed](borrow.md) (`give_back()`).

- **Returns** a [`result()`](result.md) dict: `ok`, or the `reason` of the first required step that failed, with its name as `at`. `missing` is a list of the names of the steps not done, an optional step that failed among them.
- **Pauses** yes, while the steps run.

Run again, an optional step that failed is tried again; a step that succeeds is struck off `failed`.

## `status(name)`

What the project `name` has done: a dict with `done`, the names of the steps done, in the order they were done, and `failed`, the name of each step whose last try failed to its reason. `{"done": [], "failed": {}}` for a project not run or reset. Done at once.

## `reset(name)`

Forgets what the project `name` has done, so its next `run` starts at the first step. Done at once.

## See also

[`lib/retry`](retry.md), [`lib/borrow`](borrow.md), [`lib/result`](result.md), [`memory`](../../types/memory.md), [The script library](../../../design/library.md).
