# `post_job`

```text
post_job(kind, data=None)
```

Adds an open job to the colony's job board.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `kind` | str | | what kind of job it is, such as `"haul"`; takers ask for jobs by kind |
| `data` | plain data | `None` | what the taker needs to know, such as where to haul from |

## Returns

The job's id, an int. Ids count up per colony and are never reused.

## Pauses

No, it is done at once, so the job is on the board for every member in the same tick.

## Cost

35 instructions besides the call.

## Description

`data` follows the plain-data rules of [`memory`](../types/memory.md): numbers, strings, booleans, `None`, `Pos`, and lists, tuples and dicts of these. The board keeps a copy, so changing `data` after the call does not change the job. The job is open until a member claims it with [`take_job`](take_job.md); the poster can cancel it while it is open with [`finish_job`](finish_job.md).

The script stops with an error when the bot is in no colony, when `kind` is no string, or when `data` is no plain data.

## Examples

```python
log = find_nearest("#minecraft:logs", 16)
if colony() is not None and log is not None:
    post_job("haul", [log])
```

## See also

[`take_job`](take_job.md), [`jobs`](jobs.md), [`finish_job`](finish_job.md), [`drop_job`](drop_job.md), [Colonies, Jobs](../../features/colonies.md#jobs).
