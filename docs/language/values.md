# Values & types

A script works with values: numbers, text, lists and so on. A variable is a name for a value, made by assigning to it; there is nothing to declare.

```python
logs = count("#minecraft:logs")   # an int
home_spot = home()                # a Pos
logs += 4                         # the same as logs = logs + 4
x, y, z = 10, 64, -3              # tuple unpacking: three names at once
```

`+=`, `-=`, `*=`, `/=`, `//=`, `%=` and `**=` all work.

## Numbers

`int` is a whole number, 64-bit: a result too big for that (above 9,223,372,036,854,775,807) stops the script with `int overflow`, where Python would carry on with a bigger number. A bot never needs numbers that big. `float` is a number with a point, such as `0.5` or what `pos().distance(p)` gives.

```python
print(7 + 2, 7 - 2, 7 * 2)   # 9 5 14
print(7 / 2)                 # 3.5: / always gives a float
print(7 // 2, 7 % 2)         # 3 1: whole division and its remainder
print(2 ** 10)               # 1024
print(-7 // 2)               # -4: // rounds down, as in Python
print(abs(-3), round(2.567, 1), round(2.5))   # 3 2.6 2
```

`//` and `%` are handy for grids, such as laying out a field in rows of 9:

```python
corner = pos()
for i in range(27):
    row = i // 9
    col = i % 9
    place(corner + (col, 0, row), "oak_sapling")
```

## Text

A `str` is text, in `'single'` or `"double"` quotes. `+` joins two strings, `*` repeats one, and `len` counts its characters.

```python
name = "ore"
print("iron " + name, name * 2, len(name))   # iron ore oreore 3
print("minecraft:oak_log".split(":"))      # ['minecraft', 'oak_log']
print(", ".join(["a", "b", "c"]))          # a, b, c
print("  stone ".strip(), "Stone".lower(), "stone".upper())   # stone stone STONE
print("oak_log".startswith("oak"), "oak_log".endswith("_log"))   # True True
print("oak_log".replace("oak", "birch"))  # birch_log
```

An **f-string** (an `f` before the quote) puts values into text, each between `{}`. A format spec after a `:` sets how a value looks: `:.2f` two digits after the point, `:>5` right-aligned in 5 places, `:,` a comma per thousand, `:x` hexadecimal. A field ending in `=` shows its expression too.

```python
n = 3
hp = 17.456
print(f"{n} logs, health {hp:.1f}")   # 3 logs, health 17.5
print(f"[{n:>4}] [{1234567:,}] [{255:x}]")   # [   3] [1,234,567] [ff]
print(f"{n=}")                        # n=3
width = 6
print(f"[{hp:{width}.2f}]")          # [ 17.46]: a spec can hold fields itself
```

`str.format` does the same with `{}`, `{0}` or `{name}` fields, and `%` formats the older way, as in Python:

```python
print("{} of {}".format(3, 64))               # 3 of 64
print("{1} before {0}".format("b", "a"))      # a before b
print("{item}: {n:>3}".format(item="coal", n=7))   # coal:   7
print("%d logs" % 5, "%s at %s" % ("chest", "home"))   # 5 logs chest at home
print("%(ore)s x%(n)03d" % {"ore": "iron", "n": 4})     # iron x004
```

The flags `- + space # 0`, width and precision (`*` too) and the conversions `d i u o x X e E f F g G c s r a %` work; any other conversion is an error naming it.

In a bot script, f-strings are the usual way to build a message:

```python
r = craft("stick", 4)
say(f"made {r.value} sticks" if r else f"no sticks: {r.reason}")
```

## True, False and None

`bool` is `True` or `False`. Comparisons give one: `==`, `!=`, `<`, `<=`, `>`, `>=`, and they chain as in maths, so `0 < y < 64` means `0 < y and y < 64`. `and`, `or` and `not` combine them.

`None` means "nothing": `find_nearest` gives it when it finds nothing, and `block` for a block in an unloaded chunk. Test for it with `is None` or `is not None`. `is` works only with `None`; compare other values with `==`.

```python
chest = find_nearest("chest", radius=16)
if chest is not None and 0 < chest.y < 100:
    goto(chest, near=2)
```

### Truthiness

`if` and `while` take any value, not only a `bool`. As in Python, `0`, `0.0`, `""`, `[]`, `{}`, `None`, `False` and a failed `Result` count as false; everything else counts as true. So these are the same:

```python
logs = scan("#minecraft:logs", radius=8)
if len(logs) > 0:
    say("trees!")
if logs:
    say("trees!")
```

`and` and `or` give one of their values, not always a `bool`: `a or b` is `a` when `a` is true, else `b`. That makes a quick default:

```python
target = find_nearest("chest") or home()
goto(target, near=2)
```

A conditional expression picks one of two values: `a if test else b`.

```python
torches = count("torch")
say("enough light" if torches >= 16 else f"only {torches} torches")
```

## Lists

A `list` holds values in order, between `[]`. It can change: `append` adds one value at the end, `extend` adds several, `insert(i, x)` puts one at a place, `pop()` takes the last one off (`pop(i)` the one at `i`), `remove(x)` takes the first `x` out, `index(x)` finds where `x` is, and `sort()` sorts it.

```python
todo = ["coal", "iron"]
todo.append("gold")
todo.extend(["copper", "lapis"])
todo.insert(0, "wood")
todo.remove("lapis")
print(todo, len(todo))         # ['wood', 'coal', 'iron', 'gold', 'copper'] 5
print(todo.pop(), todo.index("iron"))   # copper 2
print("coal" in todo, "dirt" not in todo)   # True True
```

**Indexing** takes one value out, counting from 0; a negative index counts from the end. **Slicing** `xs[start:stop]` takes a part, `xs[::step]` every step-th value. Strings and tuples index and slice the same way.

```python
xs = [10, 20, 30, 40, 50]
print(xs[0], xs[-1], xs[1:3], xs[:2], xs[::2])   # 10 50 [20, 30] [10, 20] [10, 30, 50]
xs[0] = 5
print(xs[0], "oak_log"[:3])                     # 5 oak
```

A **list comprehension** makes a list from another in one line: `[expression for x in xs]`, with an optional `if` to keep only some.

```python
logs = scan("#minecraft:logs", radius=16)
low_logs = [p for p in logs if p.y < pos().y + 3]
above = [p.up() for p in low_logs]
```

A comprehension's variable is its own, as in Python 3: `p` above is not a variable of the script after it.

## Tuples

A `tuple` is a list that cannot change, between `()`; a tuple of one value needs a comma, `(x,)`. Use it for a fixed group of values, such as a position: every function that takes a position also takes `(x, y, z)`.

```python
goto((100, 64, -20))
spot = (0, 64, 0)
x, y, z = spot              # unpack it into three names
```

A function can give several values at once by returning a tuple (see [Functions](functions.md)).

## Dicts

A `dict` maps keys to values, between `{}`. Keys are `str`, `int`, `bool` or `Pos`. `d[key]` reads a value (a key that is not there stops the script), `d.get(key, default)` reads one with a fallback, and `d[key] = value` sets one.

```python
stock = {"coal": 0, "iron": 0}
for ore in ["coal", "coal", "iron"]:
    stock[ore] = stock.get(ore, 0) + 1
print(stock)                      # {'coal': 2, 'iron': 1}
print(stock["coal"], stock.get("gold", 0), "iron" in stock)   # 2 0 True
print(stock.pop("iron"), stock)    # 1 {'coal': 2}
stock.setdefault("gold", 5)
print(list(stock.keys()), list(stock.values()))   # ['coal', 'gold'] [2, 5]
for name, n in stock.items():
    print(name, n)                # coal 2, then gold 5
```

`keys()`, `values()` and `items()` are live views, as in Python: they see changes to the dict made after they were taken. A dict cannot change size while a loop goes over it; loop over `list(d.keys())` to remove keys as you go.

A `Pos` as a key makes a map of the world, such as which chests hold what:

```python
contents = {}
for chest in scan("chest", radius=16):
    stacks = container(chest) or []
    contents[chest] = sum([s.count for s in stacks])
```

`memory`, the dict a bot keeps across restarts, is a dict like any other (see [Memory](../reference/types/memory.md)).

## The bot's own types

The API gives values of its own types. Each has attributes, read with a dot. In short (the [API reference](../reference/index.md#types) has them in full):

- **`Pos`**: a position, with `x`, `y`, `z`. `+` and `-` with another `Pos` or a tuple, `up(n)`, `down`, `north`, `south`, `east`, `west`, and `distance(other)`.
- **`Block`**: what `block(pos)` reads: `id`, `pos`, `is_air`, `is_fluid`, `falls`, `tags`, `state`.
- **`Stack`**: an item stack: `item` and `count`.
- **`Entity`**: a mob, player, bot or dropped item: `id`, `type`, `pos`, `health`, `valid`, `item`.
- **`Result`**: what every action gives back: `ok`, `reason`, `value`; true when the action worked (see [Errors & Result](errors.md)).

```python
here = pos()
below = block(here.down())
if below is not None and "#minecraft:logs" in below.tags:
    say(f"standing on a log at {here.x} {here.y} {here.z}")
for stack in inventory():
    print(stack.item, stack.count)
```

## Builtins

These functions are there in every script:

| Builtin | Gives |
|---|---|
| `len(x)` | the number of items in a list, tuple, dict or string |
| `range(stop)`, `range(start, stop, step=1)` | the numbers from `start` up to, not including, `stop` |
| `min(...)`, `max(...)` | the smallest or largest of several values, or of a list; take `key=` |
| `abs(x)` | `x` without its sign |
| `round(x)`, `round(x, digits)` | `x` rounded (halves to the even number, as in Python) |
| `sum(xs)` | the numbers of a list added up |
| `sorted(xs)` | a sorted copy of a list; takes `key=` and `reverse=` |
| `reversed(xs)` | the values of a list from last to first |
| `enumerate(xs)` | pairs `(index, value)` |
| `zip(a, b)` | pairs of the values of `a` and `b` side by side |
| `any(xs)`, `all(xs)` | whether any, or all, of the values are true |
| `str(x)`, `int(x)`, `float(x)`, `bool(x)` | `x` as text, a whole number, a float, a bool; `int("12")` reads text |
| `list(x)`, `tuple(x)`, `dict(x)` | a new list, tuple or dict from `x`, such as `list(d.keys())` |
| `isinstance(x, t)` | whether `x` is of type `t` (`int`, `float`, `str`, `bool`, `list`, `dict`, `tuple`, `range`, or a tuple of them) |

`print`, `debug`, `info`, `warn` and `error` write output (see [Output & logging](output.md)).

```python
print(len([1, 2, 3]), list(range(3)), list(range(2, 10, 3)))   # 3 [0, 1, 2] [2, 5, 8]
print(min(4, 2, 8), max([4, 2, 8]), sum([4, 2, 8]))             # 2 8 14
print(sorted([3, 1, 2]), sorted([3, 1, 2], reverse=True))       # [1, 2, 3] [3, 2, 1]
print(sorted(["oak", "spruce", "fir"], key=len))                # ['oak', 'fir', 'spruce']
print(list(reversed([1, 2, 3])), list(enumerate(["a", "b"])))   # [3, 2, 1] [(0, 'a'), (1, 'b')]
print(list(zip(["coal", "iron"], [3, 5])))                     # [('coal', 3), ('iron', 5)]
print(any([0, 0, 1]), all([1, 1, 0]))                          # True False
print(int("12") + 1, float("2.5"), str(7) + "!", bool([]))      # 13 2.5 7! False
print(isinstance(3, int), isinstance(3.0, (int, float)))        # True True
```

`key=` sorts by what a function gives for each value, such as the nearest chest first; the function is one you write with `def` (see [Functions](functions.md#functions-as-values)):

```python
def distance_to_me(p):
    return pos().distance(p)

chests = scan("chest", radius=16)
if chests:
    nearest = min(chests, key=distance_to_me)
    goto(nearest, near=2)
```

`ARGS` is the list of the words given after the script's name in `/antcraft bot run <script> [args]`, as strings:

```python
levels = [int(a) for a in ARGS] if ARGS else [40, 20]
```
