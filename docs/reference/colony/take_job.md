# `take_job`

```text
take_job(kind=None)
```

Claims the oldest open job of `kind` on the colony's job board for this bot.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `kind` | str or `None` | `None` | the kind of job to take; `None` takes one of any kind |

## Returns

The job as a dict, as [`jobs`](jobs.md) lists it (`id`, `kind`, `data`, `taker`, `poster`), its `taker` now this bot; `None` when there is no open job of that kind, or the bot is in no colony.

## Pauses

No, it is done at once, so no two bots take the same job, wherever their scripts pause.

## Cost

30 instructions besides the call.

## Description

The job stays this bot's until it finishes it with [`finish_job`](finish_job.md) or gives it back with [`drop_job`](drop_job.md). It also goes back to the board, open again, when the bot's script ends, fails or is stopped (by [`stop`](../bot/stop.md), `/antcraft bot stop` or starting another script on the bot), when the bot leaves the colony, and when it is removed. A bot that dies keeps its jobs: it respawns and runs its script again, and `jobs()` shows it what it held.

`data` is the bot's own copy of what was posted. A `kind` that is neither a string nor `None` stops the script with an error.

## Examples

```python
j = take_job("haul")
if j is not None:
    for p in j["data"]:
        mine(p)
    put(colony()["core"], "#minecraft:logs", 64)
    finish_job(j["id"])
```

## See also

[`post_job`](post_job.md), [`jobs`](jobs.md), [`finish_job`](finish_job.md), [`drop_job`](drop_job.md), [Colonies, Jobs](../../features/colonies.md#jobs).
