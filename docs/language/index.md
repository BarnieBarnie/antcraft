# Language guide

Bots run scripts written in a small **subset of Python 3**. Files use the `.py` extension so every editor highlights them, and what the subset has works as in Python. Anything outside the subset is a clear compile error ("`lambda` is not supported"), never silent different behaviour.

This guide is for players who know a little Python, or none. Each page shows a feature with a short bot script you can run. The bot's own functions (`goto`, `mine`, `find_nearest`, ...) are in the [API reference](../reference/index.md); this guide is about the language around them.

A first script, `config/antcraft/scripts/hello.py`, run with `/antcraft bot run hello`:

```python
say("Hello! I carry " + str(count("#minecraft:logs")) + " logs.")
chest = find_nearest("chest")
if chest is None:
    say("no chest near")
else:
    goto(chest, near=2)
    put(chest, "#minecraft:logs")
```

## Pages

- [Values & types](values.md): numbers, text, lists, dicts, tuples, the bot's own types, and the builtins that work on them.
- [Control flow](control-flow.md): `if`, `while`, `for`, `break`, `continue`, `pass` and `assert`.
- [Functions](functions.md): `def`, default and keyword arguments, `return`, `global`, and functions as `key=`.
- [Modules & import](modules.md): splitting scripts into files and using the mod's library.
- [Classes](classes.md): `class` with methods, `__init__`, single inheritance, `super()` and exception classes of your own.
- [Output & logging](output.md): `print`, `say`, and the log levels.
- [Errors & Result](errors.md): runtime errors, `Result`, and catching errors with `try`/`except`.
- [Budget & pausing](budget.md): how a script shares the server's time, and when it waits.
- [What's not supported](not-supported.md): what the subset leaves out, and how to do without it.

## Supported

- **Values:** `int` (64-bit; overflow is an error), `float`, `str`, `bool`, `None`, `list`, `dict` (keys: `str`, `int`, `bool`, `Pos`), `tuple`, and the API's own types. See [Values & types](values.md).
- **Statements:** assignment (including `+=` and friends, and tuple unpacking `a, b = ...`), `if`/`elif`/`else`, `while`, `for x in ...`, `break`, `continue`, `pass`, `def` with default and keyword arguments, `return`, `assert test, message` (a failed assert stops the script with "assertion failed" and the message), `global`, `import name`, `from name import a, b`, `try`/`except`/`else`/`finally` and `raise`, `class` with one base class. See [Control flow](control-flow.md), [Functions](functions.md), [Modules & import](modules.md), [Classes](classes.md) and [Errors & Result](errors.md).
- **Expressions:** arithmetic (`+ - * / // % **`, unary `-` and `+`), comparisons (chained `a < b < c` too), `and`/`or`/`not`, `in`/`not in`, `is`/`is not` (only for `None`), indexing and slicing, attribute access, calls, list and dict literals, conditional expressions (`a if c else b`), f-strings, list comprehensions. See [Values & types](values.md).
- **Builtins:** `print`, `debug`, `info`, `warn`, `error` (see [Output & logging](output.md)), `len`, `range`, `min`, `max`, `abs`, `round`, `sum`, `sorted`, `reversed`, `enumerate`, `zip`, `str`, `int`, `float`, `bool`, `list`, `dict`, `tuple`, `isinstance` (for the builtin types, the exception types and the script's classes), `any`, `all`. See [Values & types](values.md#builtins).
- **Methods:** the common ones of `str` (`split`, `join`, `strip`, `startswith`, `endswith`, `replace`, `lower`, `upper`, `format`), `list` (`append`, `extend`, `pop`, `insert`, `remove`, `index`, `sort`, `clear`), and `dict` (`get`, `keys`, `values`, `items`, `pop`, `setdefault`, `clear`). See [Values & types](values.md).
- **Details:** `sorted`, `min`, `max` and `list.sort` take `key=` (a function defined with `def`, or a builtin such as `len`) and `reverse=`. `dict.keys()`, `values()` and `items()` are live views, as in Python. f-strings and `format` take Python's format specs (`:.2f`, `:>5`, `:,`, `:x`, ...). A spec may hold nested fields, in an f-string (`f"{x:{width}.{digits}f}"`) as in `str.format` (`"{:{}}".format(x, width)`), and an f-string field ending in `=` shows its expression too (`f"{x=}"` gives `x=3`). `str.format` fields are `{}`, `{0}` or `{name}`, each with an optional conversion and spec. `str % args` formats as in Python (`"%d logs" % n`, `"%s at %s" % (a, b)`, `"%(name)s" % d`) with the flags `- + space # 0`, width and precision (`*` too) and the conversions `d i u o x X e E f F g G c s r a %`; any other conversion is an error naming it. A comprehension's variables are its own, as in Python 3. See [Values & types](values.md#text).
- **Truthiness** as in Python: `0`, `0.0`, `""`, `[]`, `{}`, `None`, `False` and failed `Result`s are false. See [Values & types](values.md#truthiness).

## Not supported (v1)

Multiple inheritance, special methods other than `__init__` and `@property` and other decorators on classes (see [Classes](classes.md#what-a-class-cannot-do)), `lambda`, `with`, generators and `yield`, decorators, `async`, set literals and `set`, `*args`/`**kwargs`, nested functions that capture variables (closures), `nonlocal`, the Python standard library. Several may come later. Most of them are needed less than in normal Python, because actions return results instead of raising. [What's not supported](not-supported.md) has a workaround for each, and for the smaller things the subset leaves out.
