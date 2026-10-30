# Modules & import

A script that grows can be split into files, and functions you use in many scripts can live in one file that each script imports. Such a file is a **module**. A module is a script of the script folders (see [Script folders](../features/scripts.md#script-folders)), named as scripts are: `trees.py` is `trees`, `lib/trees.py` is `lib.trees`.

Say `config/antcraft/scripts/lib/trees.py` holds:

```python
LOGS = "#minecraft:logs"
felled = 0


def is_log(p):
    b = block(p)
    return b is not None and LOGS in b.tags


def fell(base):
    """Mines the trunk at `base` from the ground up."""
    global felled
    p = base
    while is_log(p):
        if not mine(p):
            return False
        p = p.up()
    felled += 1
    return True
```

Then a script uses it in either of two ways:

```python
import lib.trees

tree = find_nearest(lib.trees.LOGS)
if tree is not None and lib.trees.fell(tree):
    say(f"{lib.trees.felled} trees down")
```

```python
from lib.trees import fell, LOGS

tree = find_nearest(LOGS)
if tree is not None:
    fell(tree)
```

## How import works

- `import trees` runs the top level of `trees.py` and binds the module to `trees`, so its names are `trees.count`, `trees.fell(...)`.
- `import lib.trees` imports `lib` and then `lib.trees`, and binds `lib`; the module is `lib.trees`. A folder without a file of its own (such as `lib/`) is a package that holds its modules.
- `from lib.trees import fell, count` binds the names given. `from lib import trees` binds the module `lib.trees`.
- `import ... as` and `from ... import *` are not supported.

A module's top level runs once per bot run, the first time any module imports it; later imports bind the module already loaded. Each module has its own globals, and a function sees the globals of the module that defined it, wherever it is called from: in the example, `fell` counts in `lib.trees.felled`, not in a `felled` of the script. The builtins, the bot API and `memory` are visible in every module; `ARGS` is a global of the script the bot runs, so pass what a module needs of it as arguments.

## When an import fails

A module that is not there fails the script at the line of the import (`No module named 'lib.trees'`), and so does a name a module does not have (`cannot import name 'fel' from 'lib.trees'`). A module that does not compile fails the script with its own file and line (`lib/trees.py:4: ...`), and an error while a module runs names that module's file. Circular imports fail with the cycle: `circular import: lib.a -> lib.b -> lib.a`.

## The mod's library

The mod's default library modules live under `lib/` and are copied with `idle.py` (see [Scripts in the game](../features/scripts.md)): `lib/home.py` (`go_home()`, `at_home()`, `to_home_dimension()`, `portal_out()`), `lib/shaft.py` and `lib/mine.py` (see [Mining](../features/mining.md)), and `lib/craft.py` (see [`lib/craft`](../reference/modules/lib/craft.md)). The default colony scripts live under `colony/` (see [Default colony scripts](../features/colony-scripts.md)), and their shared helpers in `colony/common.py`. Reading them is a good way to learn the language.

```python
from lib.home import go_home

if not go_home():
    say("cannot find my way home")
```

## Scripts from data packs

A data pack's scripts, `data/<namespace>/script/**/*.py`, are modules too, named by their id with dots for the colon and the slashes: `data/mypack/script/lib/trees.py` has the id `mypack:lib/trees` and is imported with `import mypack.lib.trees` or `from mypack.lib.trees import x`. A namespace or path with a `-` cannot be imported, since a module name is made of Python names, but such a script can still be run. A script in a script folder of the same name, such as `config/antcraft/scripts/mypack/lib/trees.py`, wins (see [Script folders](../features/scripts.md#script-folders)).

## Reloading

A bot imports modules from the library as it was when its script started; `/antcraft script reload` restarts a bot whose script, or a module it imported, changed. The [standard library](not-supported.md#the-python-standard-library) of Python (`math`, `random`, ...) is not there.
