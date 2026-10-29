# Errors & Result

There are two kinds of things that go wrong in a script: mistakes in the script, and things in the world that did not work out. The language treats them differently.

## Mistakes stop the script

A **compile error** is reported when the script is loaded, before the bot does anything: `miner.py:12:5: expected ':' after 'if' condition`. `/antcraft script list` shows the error of each script that does not compile.

A **runtime error** is a mistake found while the script runs: reading an attribute of `None`, an index out of range, a name that is not defined, `1 / 0`. It stops the script, is logged at level `error` (so it shows in the owner's chat and in the bot's log, see [Output & logging](output.md)), and the bot falls back to `idle.py`. The message names the file and line:

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

## Why there is no try/except

In Python, a failure raises an exception, and `try`/`except` catches it. AntCraft has no `try`, `except` or `raise`, on purpose:

- **The common failures are not exceptions.** A blocked path or a full chest is an everyday event for a bot, and a `Result` makes it an ordinary value the script looks at, right where it called the action. Nothing jumps out of the middle of a loop.
- **The rest are mistakes.** A runtime error is a bug in the script: catching it would hide it. Stopping, with the line in chat, shows it at once.
- **Scripts stay simple and saveable.** A script pauses in the middle of an action and is saved with the world in that state (see [Budget & pausing](budget.md)); no handlers to keep around makes that simpler.

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
