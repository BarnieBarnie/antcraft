# Budget & pausing

Many bots run their scripts on one server, and Minecraft runs 20 ticks a second. So a script does not run on its own: each tick, every bot runs its script a little, and then the game goes on. This page is about what that means for the scripts you write.

## Actions pause the script

An **action** takes game time: walking somewhere, mining a block, smelting. When a script calls one, the script pauses there, the bot does the action over as many ticks as it takes, and the script goes on with the action's `Result` as the value of the call. You write it as an ordinary call, one line after the other:

```python
tree = find_nearest("#minecraft:logs")
if tree is not None:
    goto(tree, near=2)      # the script waits here until the bot arrives (or gives up)
    mine(tree)              # and here until the log is broken
say("done")
```

`wait(ticks)` pauses on purpose, for that many ticks (20 is a second). Use it in a loop that waits for something to change, such as crops to grow or a chest to fill:

```python
farm = find_nearest("wheat", radius=16)
while farm is not None:
    b = block(farm)
    if b is not None and b.state.get("age") == 7:
        mine(farm)
        place(farm, "wheat_seeds")
    wait(600)               # check again in thirty seconds
```

**Senses** (`pos`, `block`, `count`, `find_nearest`, ...) answer at once and do not pause. The ones that search a large area (`find_nearest`, `scan`, `mineshaft`, `reachable`) share a budget across the server; when a search cannot finish within this tick's share, it goes on in the next tick, and the script pauses until it has the answer. For the script, it is just a call that took a while.

## The instruction budget

Between pauses, a script runs instructions: its arithmetic, comparisons, calls and so on. Each bot gets `scriptInstructionsPerTick` of them a tick (see [Config](../features/config.md); default 2000). A script that uses them up simply continues on the next tick, where it stopped. So a loop that never pauses costs a busy bot, not a frozen server:

```python
total = 0
for i in range(100000):     # takes several ticks: the bot thinks for a while
    total += i
print(total)
```

Senses that do work in the world count more than one instruction, about as many as the time they take (`inventory` 30, `entities` 120, ...), and searches count by the blocks they read; [Budget](../features/scripts.md#budget) lists the costs.

In practice:

- Most scripts never notice the budget: they spend their time in actions.
- A loop that only senses, waiting for something, should `wait` each round. Without it, the bot asks thousands of times a second, uses its whole budget and gets no faster answer.
- Heavy searches (`scan` with a large radius, `mineshaft`) are best done once and kept in a variable, or in `memory`, rather than in every round of a loop.

```python
# Ask once, not every round
logs = scan("#minecraft:logs", radius=24, limit=32)
for log in logs:
    mine(log)
```

## Pausing and saving

Because a script is paused between ticks anyway, the server can save it at any pause: a running script is saved with the world and, after a restart, goes on at the same line, inside the action it was in. Your variables come back too. If you edit the script (or a module it imports) in the meantime, it starts again from the top instead, and only `memory` is kept. So keep what must survive an edit, such as the position of a mine, in `memory`:

```python
if "mine_top" not in memory:
    site = mineshaft([40, 20])
    if site is not None:
        memory["mine_top"] = site["top"]
```

See [Save and resume](../features/scripts.md#save-and-resume) for the details.
