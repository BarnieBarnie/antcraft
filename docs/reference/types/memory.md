# `memory` and `colony_memory`

`memory` is a dict that belongs to the bot and is saved with it. It survives restarts and script edits. Values must be plain data: numbers, strings, booleans, `None`, [`Pos`](pos.md), and lists, tuples and dicts of these. Storing anything else (a function, a `Stack`, an `Entity`, or a list holding one) stops the script with an error at the line of the store. A list or dict already in `memory` can still be changed in place; a value that is no longer plain data when the bot is saved is left out of the save, with a warning in the bot's log.

```python
if "chest" not in memory:
    memory["chest"] = find_nearest("chest")
```

`colony_memory` is a dict shared by every member of the bot's colony, under the same plain-data rules, and saved with the colony; it is `None` for a bot in no colony. What one member stores the others see at once. It is never saved with a bot: a bot saved while its script holds `colony_memory`, in the global or a variable of its own, resumes with the colony's dict as it is then. A list or dict taken out of it into a variable is the bot's own copy after a resume. The Colony Core stores items like a chest, so `container`, `put` and `take` work on `colony()["core"]`.

```python
c = colony()
if c is not None and "chests" not in colony_memory:
    colony_memory["chests"] = [c["core"]]
```
