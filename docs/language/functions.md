# Functions

A function is a named piece of script you can call many times. `def` makes one: its name, its parameters between `()`, and its lines indented under it. `return` gives a value back and leaves the function; a function that ends without one gives `None`.

```python
def is_log(p):
    b = block(p)
    return b is not None and "#minecraft:logs" in b.tags


def fell(base):
    p = base
    while is_log(p):
        mine(p)
        p = p.up()
    pick_up("#minecraft:logs")


tree = find_nearest("#minecraft:logs")
if tree is not None:
    fell(tree)
```

A string as the first line of a function is its description (a docstring), as in Python; it does nothing when the script runs.

## Default and keyword arguments

A parameter can have a default, used when the call leaves it out. A call can name its arguments, in any order, after the ones given by place. The bot API works the same way: `find_nearest("chest", radius=8)`.

```python
def gather(item, n=16, radius=16):
    """Picks up dropped `item` until the bot carries `n` of it, or none is left near."""
    while count(item) < n:
        if not pick_up(item, radius=radius):
            return False
    return True


gather("cobblestone")
gather("#minecraft:logs", 32)
gather("wheat", radius=8)
```

## Spreading a list into arguments

`*` before an argument passes the items of a list, tuple or other sequence as arguments by place, as in Python. `**` before a dict passes its items as keyword arguments. Both mix with other arguments, also with more than one `*` or `**`, and work for the bot API too:

```python
p = (10, 64, -3)
print(*p)                                       # 10 64 -3
opts = {"radius": 8}
gather("wheat", **opts)                         # gather("wheat", radius=8)
```

A value that is not a sequence after `*`, or not a dict after `**`, stops the script (`f() argument after * must be an iterable, not int`, `f() argument after ** must be a mapping, not list`), and so does a keyword given twice (`f() got multiple values for keyword argument 'radius'`). As in Python, no positional argument or `*` may come after a `**`.

## Taking any number of arguments

A parameter `*args` takes the positional arguments left over after the others, as a tuple; `**kwargs` takes the keyword arguments no other parameter names, as a dict. They come last, `**kwargs` after `*args`. That is how a function can pass on whatever it is called with:

```python
def twice(action, *args, **kwargs):
    action(*args, **kwargs)
    action(*args, **kwargs)


twice(place, pos().north(), "torch")
twice(gather, "wheat", radius=8)
```

Parameters after `*args` (keyword-only parameters) and a bare `*` are not supported.

## Several values back

Return a tuple to give several values, and unpack it where you call:

```python
def nearest_with_distance(what):
    """The nearest `what` and how far it is, or (None, 0)."""
    p = find_nearest(what)
    if p is None:
        return None, 0
    return p, pos().distance(p)


chest, far = nearest_with_distance("chest")
if chest is not None and far < 20:
    goto(chest, near=2)
```

A function can also give back a dict when the values have names, as `lib.shaft.dig_shaft` does with `ok`, `reason` and `missing`.

## Variables inside and outside

A variable assigned in a function is the function's own (local): it is gone when the function returns, and it hides a script variable of the same name. A function can read the script's variables (globals) without more ado. To assign one, say `global` first:

```python
trips = 0


def store():
    global trips
    chest = find_nearest("chest")
    if chest is not None and put(chest, "#minecraft:logs"):
        trips += 1


store()
say(f"{trips} trips so far")
```

A variable that should outlive the script, or a restart, belongs in `memory` instead (see [Memory](../reference/types/memory.md)).

A function can call itself (recursion), up to a limit of depth; most bot work is clearer as a loop.

## Functions as values

A function is a value like any other: its name without `()` can be passed on. That is how `sorted`, `min`, `max` and `list.sort` take `key=`: a function that gives, for each value, what to sort by. A builtin such as `len` works too.

```python
def height(p):
    return p.y


logs = scan("#minecraft:logs", radius=8)
logs.sort(key=height)                           # lowest first
highest = max(logs, key=height) if logs else None
names = sorted(["spruce", "oak", "birch"], key=len)
```

There is no `lambda`: write a small `def` instead.

## Not in the subset

Keyword-only parameters, `lambda`, decorators and closures (a function inside a function that uses the outer one's variables) are not supported. A function defined inside another works as long as it uses only its own variables and the script's globals. See [What's not supported](not-supported.md) for workarounds.
