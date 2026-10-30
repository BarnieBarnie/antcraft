# What's not supported

The language is a subset of Python 3. What it leaves out is a compile error that names the construct and its place, such as ``miner.py:3:5: `lambda` is not supported``, never something that quietly works differently. Several of these may come later. Most are needed less than in normal Python, because actions return results instead of raising.

Here is each one, with what to write instead.

## Parts of classes

[Classes](classes.md) work, with one base class each. Multiple inheritance, metaclasses, `@property` and the other decorators, special methods other than `__init__` (`__str__`, `__eq__`, `__add__`, ...) and `__slots__` do not; [What a class cannot do](classes.md#what-a-class-cannot-do) lists them with what to write instead.

An instance does not go into `memory`, which holds plain data only. Keep the values you need in a dict there, and make the instance again from it after a restart.

## lambda

Write a small function with `def` and pass its name:

```python
def by_height(p):
    return p.y


logs = sorted(scan("#minecraft:logs", radius=8), key=by_height)
```

## with

`with` manages resources such as open files; a script has no files to open. There is nothing to write instead.

## Generators and yield

There are no generators, `yield` or generator expressions (`sum(x for x in xs)`). Build a list instead, with a loop or a list comprehension, and pass that: `sum([s.count for s in inventory()])`. A function that would yield values one by one can return the list of them.

## Decorators

There are no decorators (`@something` over a `def`). Call the wrapping function yourself where you need it.

## async

There is no `async` and no `await`, and no need for them: every bot runs its script at the same time as the others, and an action pauses only its own script (see [Budget & pausing](budget.md)). Bots that work together share state through `colony_memory` and the job board.

## Set literals and set

There are no sets (`{1, 2}`, `set(xs)`). A list with `in` does for a few values; a dict with `True` as each value does for many, and works with `Pos` as keys:

```python
done = {}
for log in scan("#minecraft:logs", radius=16):
    if log in done:
        continue
    mine(log)
    done[log] = True
```

To drop duplicates from a list: `unique = []` and then `if x not in unique: unique.append(x)` for each value.

## Keyword-only parameters

`def f(*args, **kwargs)` and `f(*xs, **d)` work as in Python (see [Functions](functions.md#taking-any-number-of-arguments)), but a parameter after `*args`, or after a bare `*`, is not supported (`keyword-only parameters are not supported`), and neither is `/` among the parameters. Put such a parameter before `*args` with a default, or read it from `**kwargs`:

```python
def store_all(chest, *items, **opts):
    for item in items:
        put(chest, item, opts.get("n"))
```

## Closures and nonlocal

A function defined inside another may not use the outer function's variables (`closures are not supported`), and there is no `nonlocal`. Define the function at the top level and give it what it needs as arguments; to change a value, return the new one, or keep it in a dict or list the caller passes in.

```python
def nearest_of(points, to):
    best = None
    for p in points:
        if best is None or to.distance(p) < to.distance(best):
            best = p
    return best


chest = nearest_of(scan("chest", radius=16), pos())
```

A function defined inside another works as long as it uses only its own variables and the module's globals.

## else on a loop

`for ... else` and `while ... else` are not supported. Set a flag before the loop:

```python
found = None
for p in scan("chest", radius=16):
    stacks = container(p)
    if stacks is not None and len(stacks) == 0:
        found = p
        break
if found is None:
    say("no empty chest")
```

## The Python standard library

There is no `import math`, `random`, `json` or any other standard module: `import` finds only modules of the script folders (see [Modules & import](modules.md)). The builtins cover most needs: `abs`, `round`, `min`, `max`, `sum`, `sorted`. For the rest, write a small function, or a module of your own:

```python
def sqrt(x):
    return x ** 0.5


def clamp(x, low, high):
    return max(low, min(x, high))
```

## Smaller things

These are compile errors too, each with an easy way around:

| Not supported | Instead |
|---|---|
| `is` with anything but `None` | `==` |
| `del x`, `del d[key]` | `d.pop(key)`, `xs.pop(i)`, or assign `None` |
| `import x as y`, `from x import *` | `import x`, or `from x import a, b` |
| dict comprehensions `{k: v for ...}` | a loop that sets `d[k] = v` |
| `a, *rest = xs` | `a = xs[0]` and `rest = xs[1:]` |
| the bit operators `& \| ^ ~ << >>` | `//` and `%` by powers of 2 |
| `:=` | assign on the line before |
| type annotations (`x: int = 1`) | leave them out |
| bytes (`b"..."`), complex numbers (`1j`), `...` | not needed for bots |
