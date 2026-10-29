# Language guide

!!! note
    A full language guide comes later (#404). Until then, this page lists what the language supports.

Files use the `.py` extension so every editor highlights them, but the language is a documented **subset of Python 3**. Anything outside the subset is a clear compile error ("`class` is not supported"), never silent different behaviour.

## Supported

- **Values:** `int` (64-bit; overflow is an error), `float`, `str`, `bool`, `None`, `list`, `dict` (keys: `str`, `int`, `bool`, `Pos`), `tuple`, and the API's own types (see below).
- **Statements:** assignment (including `+=` and friends, and tuple unpacking `a, b = ...`), `if`/`elif`/`else`, `while`, `for x in ...`, `break`, `continue`, `pass`, `def` with default and keyword arguments, `return`, `assert test, message` (a failed assert stops the script with "assertion failed" and the message), `global`, `import name`, `from name import a, b`.
- **Expressions:** arithmetic (`+ - * / // % **`, unary `-` and `+`), comparisons (chained `a < b < c` too), `and`/`or`/`not`, `in`/`not in`, `is`/`is not` (only for `None`), indexing and slicing, attribute access, calls, list and dict literals, conditional expressions (`a if c else b`), f-strings, list comprehensions.
- **Builtins:** `print`, `debug`, `info`, `warn`, `error` (see Output and logging), `len`, `range`, `min`, `max`, `abs`, `round`, `sum`, `sorted`, `reversed`, `enumerate`, `zip`, `str`, `int`, `float`, `bool`, `list`, `dict`, `isinstance` (for the builtin types), `any`, `all`.
- **Methods:** the common ones of `str` (`split`, `join`, `strip`, `startswith`, `endswith`, `replace`, `lower`, `upper`, `format`), `list` (`append`, `extend`, `pop`, `insert`, `remove`, `index`, `sort`), and `dict` (`get`, `keys`, `values`, `items`, `pop`, `setdefault`).
- **Details:** `sorted`, `min`, `max` and `list.sort` take `key=` (a function defined with `def`, or a builtin such as `len`) and `reverse=`. `dict.keys()`, `values()` and `items()` are live views, as in Python. f-strings and `format` take Python's format specs (`:.2f`, `:>5`, `:,`, `:x`, ...). A spec may hold nested fields, in an f-string (`f"{x:{width}.{digits}f}"`) as in `str.format` (`"{:{}}".format(x, width)`), and an f-string field ending in `=` shows its expression too (`f"{x=}"` gives `x=3`). `str.format` fields are `{}`, `{0}` or `{name}`, each with an optional conversion and spec. `str % args` formats as in Python (`"%d logs" % n`, `"%s at %s" % (a, b)`, `"%(name)s" % d`) with the flags `- + space # 0`, width and precision (`*` too) and the conversions `d i u o x X e E f F g G c s r a %`; any other conversion is an error naming it. A comprehension's variables are its own, as in Python 3.
- **Truthiness** as in Python: `0`, `0.0`, `""`, `[]`, `{}`, `None`, `False` and failed `Result`s are false.

## Not supported (v1)

`class`, `try`/`except`/`raise`, `lambda`, `with`, generators and `yield`, decorators, `async`, set literals and `set`, `*args`/`**kwargs`, nested functions that capture variables (closures), `nonlocal`, the Python standard library. Several may come later. Most of them are needed less than in normal Python, because actions return results instead of raising.

## Errors

A compile error is reported when the script is loaded: `miner.py:12:5: expected ':' after 'if' condition`. A runtime error (`None` has no attribute `x`, index out of range, unknown function) stops the script, is logged at level `error` (so it shows in chat and in the bot log, see Output and logging), and the bot falls back to `idle.py`. Messages name the line and, where possible, hint at the cause: `miner.py:18: chest is None (find_nearest found nothing?)`.

## Modules

A module is a script of the script folders (see Files and commands), named as scripts are: `trees.py` is `trees`, `lib/trees.py` is `lib.trees`.

- `import trees` runs the top level of `trees.py` and binds the module to `trees`, so its names are `trees.count`, `trees.fell(...)`.
- `import lib.trees` imports `lib` and then `lib.trees`, and binds `lib`; the module is `lib.trees`. A folder without a file of its own (such as `lib/`) is a package that holds its modules.
- `from lib.trees import fell, count` binds the names given. `from lib import trees` binds the module `lib.trees`.
- `import ... as` and `from ... import *` are not supported.

A module's top level runs once per bot run, the first time any module imports it; later imports bind the module already loaded. Each module has its own globals, and a function sees the globals of the module that defined it, wherever it is called from. The builtins, the bot API and `memory` are visible in every module; `ARGS` is a global of the script the bot runs.

A module that is not there fails the script at the line of the import (`No module named 'lib.trees'`), and so does a name a module does not have (`cannot import name 'fel' from 'lib.trees'`). A module that does not compile fails the script with its own file and line (`lib/trees.py:4: ...`), and an error while a module runs names that module's file. Circular imports fail with the cycle: `circular import: lib.a -> lib.b -> lib.a`.

The mod's default library modules live under `lib/` and are copied with `idle.py` (see Files and commands); for now `lib/home.py` (`go_home()`, `at_home()`, `to_home_dimension()`, `portal_out()`), `lib/shaft.py` and `lib/mine.py`. The default colony scripts live under `colony/` (see Colony scripts). A bot imports modules from the library as it was when its script started; `/antcraft script reload` restarts a bot whose script, or a module it imported, changed.
