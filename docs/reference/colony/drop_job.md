# `drop_job`

```text
drop_job(id)
```

Gives a job this bot took back to the colony's job board, open again.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `id` | int | | the job's id, `job["id"]` of the job [`take_job`](take_job.md) gave |

## Returns

`True` if the job is open again; `False` when no such job is on the board, it is open, another bot took it, or the bot is in no colony.

## Pauses

No, it is done at once.

## Cost

20 instructions besides the call.

## Description

For a job the bot cannot do now, such as when it lacks the tools: another member can take it, or this one again later. Jobs also go back to the board by themselves when the taker's script ends (see [`take_job`](take_job.md)). To remove a job instead, use [`finish_job`](finish_job.md).

## Examples

```python
j = take_job("mine")
if j is not None and not has("#minecraft:pickaxes"):
    drop_job(j["id"])
```

## See also

[`take_job`](take_job.md), [`finish_job`](finish_job.md), [`jobs`](jobs.md), [Colonies, Jobs](../../features/colonies.md#jobs).
