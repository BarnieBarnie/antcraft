# `finish_job`

```text
finish_job(id)
```

Takes a job off the colony's job board: one this bot took, as done, or an open one it posted, to cancel it.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `id` | int | | the job's id, as [`post_job`](post_job.md) returned it or `job["id"]` |

## Returns

`True` if the job was taken off the board; `False` when no such job is on the board, it is another bot's (taken by another, or open and posted by another), or the bot is in no colony.

## Pauses

No, it is done at once.

## Cost

20 instructions besides the call.

## Description

A taken job can be finished only by its taker, not by its poster. To give a job back for another bot to do instead, use [`drop_job`](drop_job.md).

## Examples

```python
j = take_job("smelt")
if j is not None:
    if smelt(j["data"], 8):
        finish_job(j["id"])
    else:
        drop_job(j["id"])
```

## See also

[`take_job`](take_job.md), [`drop_job`](drop_job.md), [`post_job`](post_job.md), [Colonies, Jobs](../../features/colonies.md#jobs).
