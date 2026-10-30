# Colonies

**Colonies:** craft a Colony Core (chest in the middle, iron ingots in the corners, blocks of copper on the sides) and place it: you own the colony, it stores 54 stacks, and bots activated within 32 blocks of it by a player who may change it join it. To move a bot into a colony placed later, or into another one, use `/antcraft bot join <core pos>`; `/antcraft bot leave` takes it out (see [Commands](commands.md)). A colony is its core, its members, its radius and who may change it; what its members do is up to their scripts (the goal-driven colony, which surveyed its area, posted jobs and built blueprints by itself, was removed in milestone 4, see [Design](../design/index.md#milestones)). The mod's [default colony scripts](colony-scripts.md) give a working colony out of the box, and an example to change: run them on members with `/antcraft bot run colony.<role>`, or set one on a Bot Core with `/antcraft core script colony.<role>`.

They share `colony/common.py`. Scripts know their colony: `colony()` gives a member its colony's core (with the core's dimension), radius, members and owner, and `colony_memory` is a dict every member shares, saved with the colony. Members split work through the colony's job board: `post_job(kind, data)`, `take_job(kind)`, `finish_job(id)`, and a job whose taker's script ends or is stopped goes back to the board. A member stays one in another dimension than its core. Worlds saved with a goal-driven colony load: the colony keeps its core, members, radius, owner and trusted players, and its jobs, stock targets, orders, projects, builds and mineshaft are dropped. Breaking the core dissolves the colony and drops its contents.

## Jobs

A colony has a job board, so its members split work by role: one posts "haul these logs", another takes it, and no two take the same job. A script can pause anywhere when it uses up its budget, so a board kept in `colony_memory` could not claim a job in one step; these natives do, at once:

| Call | |
|---|---|
| `post_job(kind, data=None)` | adds an open job: `kind` a string, `data` plain data (a copy is kept); returns its id, an int that counts up per colony. Outside a colony it fails the script at its line |
| `jobs(kind=None)` | the open and taken jobs of `kind` (or of every kind), oldest first, as dicts: `id`, `kind`, `data`, `taker` (the bot's UUID string, or `None` while open) and `poster`. `[]` outside a colony |
| `take_job(kind=None)` | claims the oldest open job of `kind` (or of any kind) for this bot and returns it as `jobs` lists it, or `None` when there is none, or outside a colony |
| `finish_job(id)` | removes the job this bot took, or an open job this bot posted (to cancel it); `True` if it did. Another bot's job stays: `False` |
| `drop_job(id)` | gives a job this bot took back to the board, open again; `True` if it did, `False` for an open job or another bot's |

A job does not stay claimed forever: it goes back to the board when its taker's script ends, fails or is stopped (also by `/antcraft bot stop`, or by starting another script on the bot), when the taker leaves the colony, and when the bot is removed. A bot that dies keeps its jobs: it respawns and runs its script again, and `jobs()` shows it what it held. The board is saved with the colony.

```python
j = take_job("haul")
if j is not None:
    for p in j["data"]:
        take(p, "oak_log")
    put(colony()["core"], "oak_log")
    finish_job(j["id"])
```
