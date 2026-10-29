# Control flow

A script runs from the top down, one line after the other. `if`, `while` and `for` change that. The lines they control are the ones indented under them (four spaces is usual), after a line ending in `:`.

## if, elif, else

`if` runs its lines only when its test is true (see [Truthiness](values.md#truthiness)). `elif` tries another test when the ones before failed, and `else` runs when none held.

```python
logs = count("#minecraft:logs")
if logs == 0:
    say("no logs at all")
elif logs < 16:
    say(f"only {logs} logs")
else:
    say("plenty of logs")
```

## while

`while` repeats its lines as long as its test is true. `while True:` repeats for ever, the usual shape of a worker script: do a round of work, wait, and again.

```python
while count("cobblestone") < 64:
    stone = find_nearest("stone", radius=8)
    if stone is None:
        break
    mine(stone)
```

A loop that never pauses does not freeze the server: a script gets a budget of instructions a tick and simply goes on in the next tick (see [Budget & pausing](budget.md)). Still, give an idle loop a `wait`, so the bot does not spend its budget asking the same question many times a tick:

```python
while True:
    if not is_day():
        goto(home())
    wait(100)  # five seconds
```

## for

`for x in ...` runs its lines once for each value of a list, tuple, string, dict (its keys) or `range`.

```python
for ore in scan("#c:ores/iron", radius=8):
    mine(ore)

for i in range(3):
    craft("torch")

for i, stack in enumerate(inventory()):
    print(i, stack.item, stack.count)
```

## break, continue and pass

`break` leaves the loop at once. `continue` skips the rest of this round and goes on with the next value. `pass` does nothing; it fills a place where a line is needed but nothing is to be done.

```python
for log in scan("#minecraft:logs", radius=16):
    if log.y > pos().y + 5:
        continue            # too high: skip this one
    if not mine(log):
        break               # stop at the first that failed
chest = find_nearest("chest")
for stack in inventory():
    if stack.item == "minecraft:dirt":
        pass                # keep dirt for pillaring
    else:
        put(chest, stack.item)
```

## assert

`assert test, message` stops the script when `test` is false, with "assertion failed" and the message in the bot's log and its owner's chat. Use it for something the script cannot go on without:

```python
chest = find_nearest("chest", radius=32)
assert chest is not None, "no chest within 32 blocks"
goto(chest, near=2)
```

Without a message, the error says only "assertion failed" and the line.

## Not in the subset

`else` on a loop (`for ... else`, `while ... else`) is not supported: set a flag instead (see [What's not supported](not-supported.md#else-on-a-loop)). Nor are `try` and `with`.
