# Errors & Result

There are two kinds of things that go wrong in a script: mistakes in the script, and things in the world that did not work out. The language treats them differently.

## Mistakes stop the script

A **compile error** is reported when the script is loaded, before the bot does anything: `miner.py:12:5: expected ':' after 'if' condition`. `/antcraft script list` shows the error of each script that does not compile.

A **runtime error** is a mistake found while the script runs: reading an attribute of `None`, an index out of range, a name that is not defined, `1 / 0`. Unless a `try` catches it (see [Catching errors](#catching-errors)), it stops the script, is logged at level `error` (so it shows in the owner's chat and in the bot's log, see [Output & logging](output.md)), and the bot falls back to `idle.py`. The message names the file and line:

```text
miner.py:18: 'NoneType' object has no attribute 'x'
miner.py:7: name 'find_nearst' is not defined
miner.py:22: list index out of range
miner.py:30: assertion failed: no chest within 32 blocks
```

Fix the script and run it again (or `/antcraft script reload` to restart the bots running it).

## Things that fail in the world give a Result

Walking, mining, crafting and every other **action** of the bot can fail for reasons that are not a mistake: the path is blocked, the chest is full, a creeper took the block. An action never stops the script. It gives back a **`Result`**:

- `r.ok`: `True` if it worked;
- `r.reason`: why not, a short code such as `"unreachable"`, `"no_space"` or `"missing_ingredients"` (`None` when it worked); the [reason codes](../reference/types/result.md#reason-codes) list them all;
- `r.value`: what it produced, such as how many items `craft` made.

A `Result` is true when the action worked and false when it failed, so it goes straight into an `if`:

```python
chest = find_nearest("chest")
if not goto(chest, near=2):
    say("cannot get to the chest")
r = put(chest, "#minecraft:logs")
if r:
    say(f"stored {r.value} logs")
elif r.reason == "no_space":
    say("the chest is full")
else:
    say(f"could not store the logs: {r.reason}")
```

Senses (`find_nearest`, `block`, `container`, ...) never fail: they give `None` or an empty list when there is nothing to find. Check for it before you use the value.

## Catching errors

`try`, `except`, `else`, `finally` and `raise` work as in Python. A runtime error inside `try` jumps to the first `except` clause that matches it, and the script goes on after the `try`:

```python
def parse_count(text):
    try:
        return int(text)
    except ValueError as e:
        warn(f"not a number: {e}")
        return 0
    finally:
        debug("parsed", text)


n = parse_count(ARGS[0]) if len(ARGS) > 0 else 16
```

- `except` alone catches every error; `except ValueError`, `except (IndexError, KeyError)` and `except Exception as e` catch the errors of those types. The first clause that matches runs; an error no clause matches goes on outwards, to a `try` around this one or in a calling function, or stops the script.
- `else` runs when the `try` body raised nothing; `finally` runs however the `try` is left: at its end, after an error (caught or not), and on `return`, `break` and `continue`.
- `str(e)` is the error's message, such as `list index out of range`; `isinstance(e, KeyError)` tests its type.
- `raise ValueError("bad count")` raises an error of your own; `raise` alone, inside an `except` clause, raises the error it handles again, with its line. A bare `raise` anywhere else is a compile error. `raise ... from` is not supported.

A script saved with the world in the middle of a `try`, an `except` or a `finally` (waiting in an action there, say) goes on there after the restart, as if it had never stopped.

### Exception types

There are no classes, so the exception types are the builtin ones below. Each error the language raises has the type CPython gives the same error, and every type derives from `Exception`:

| Type | Raised for |
| --- | --- |
| `Exception` | Catches every error below. |
| `AssertionError` | A failed `assert`. |
| `AttributeError` | An attribute the value does not have. |
| `ImportError` | A module that is not there, a name it does not have, or a circular import. |
| `IndexError` | An index out of range, `pop` from an empty list. |
| `KeyError` | A key that is not in the dict. |
| `NameError` | A name that is not defined, or a local read before it is set. |
| `OverflowError` | An int that does not fit in 64 bits. |
| `RuntimeError` | A dict changed while a loop goes over it; an action that needs a bot in a world. |
| `RecursionError` | Functions calling each other too deep; it derives from `RuntimeError`. |
| `SyntaxError` | A module that does not compile, when it is imported. |
| `TypeError` | A value of the wrong type, such as `1 + "a"`, or wrong arguments to a call. |
| `ValueError` | A value of the right type that does not fit, such as `int("x")` or `drop(n=0)`. |
| `ZeroDivisionError` | Dividing by zero. |

### What a try does not catch

Only runtime errors of the script are exceptions. These are not, and no `try` catches them:

- an action that fails in the world: it gives back a [`Result`](#things-that-fail-in-the-world-give-a-result), which stays a value;
- the script pausing when its [instruction budget](budget.md) runs out, which it does not notice;
- the bot being stopped (`/antcraft bot stop`, `stop()`), the script restarting after `/antcraft script reload`, or the server shutting down;
- a bug in AntCraft itself, which stops the script with an error as before.

## Results or exceptions

Prefer a `Result` and a check to `try`, for the reasons the language was built around:

- **The common failures are not exceptions.** A blocked path or a full chest is an everyday event for a bot, and a `Result` makes it an ordinary value the script looks at, right where it called the action. Nothing jumps out of the middle of a loop.
- **The rest are mostly mistakes.** A runtime error is usually a bug in the script: catching it with a bare `except` hides it. Stopping, with the line in chat, shows it at once.

Use `try` where an error is expected and you can do something about it, such as text from `ARGS` that may not be a number.

## What to do instead

**Check before you use a value.** Most runtime errors are a `None` used as if it were something:

```python
log = find_nearest("#minecraft:logs")
if log is None:
    say("no trees near")
    stop()
mine(log)
```

**Use `.get` and `in` for dicts and `len` for lists**, instead of catching a missing key or index:

```python
counts = memory.get("counts", {})
iron = counts.get("iron", 0)
todo = memory.get("todo", [])
next_spot = todo[0] if len(todo) > 0 else None
```

**Give a value a default** when it may be missing, such as the arguments of `/antcraft bot run`:

```python
n = int(ARGS[0]) if len(ARGS) > 0 else 16
```

**Retry an action** a few times, waiting between tries:

```python
def goto_retrying(p, near=0, tries=3):
    r = goto(p, near=near)
    for _ in range(tries - 1):
        if r or r.reason not in ["unreachable", "timeout", "not_loaded"]:
            break
        wait(40)
        r = goto(p, near=near)
    return r
```

**Give failures back from your own functions** the way actions do: return a value that says what went wrong, and let the caller decide. A dict, or a tuple `(ok, reason)`, works well:

```python
def fetch(item, n):
    """Takes `n` of `item` from the nearest chest. Gives (True, None), or (False, why)."""
    chest = find_nearest("chest")
    if chest is None:
        return False, "no chest"
    r = take(chest, item, n)
    if not r:
        return False, r.reason
    return True, None


ok, why = fetch("coal", 8)
if not ok:
    say(f"no coal: {why}")
```

**Stop on purpose** when the script cannot go on: `assert test, "message"` for something that must hold, or `error("message")` and then `stop()` to explain first.
