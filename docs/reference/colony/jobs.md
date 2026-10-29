# `jobs`

```text
jobs(kind=None)
```

The jobs on the colony's job board, open and taken.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `kind` | str or `None` | `None` | the kind of job to list; `None` lists every kind |

## Returns

A list of dicts, oldest job first, `[]` for a bot in no colony. Each job has:

- `id`: its id, an int.
- `kind`: its kind, a string.
- `data`: a copy of the data it was posted with.
- `taker`: the UUID string of the bot that took it, or `None` while it is open.
- `poster`: the UUID string of the bot that posted it.

## Pauses

No, it is done at once.

## Cost

10 instructions besides the call, and 8 for each job it lists.

## Description

A snapshot: the board can change as soon as the script pauses. To claim a job, use [`take_job`](take_job.md), not `jobs()`, as two bots can see the same open job at once. A bot can find the jobs it holds by its `taker`, such as after it respawned. A `kind` that is neither a string nor `None` stops the script with an error.

## Examples

```python
open_hauls = [j for j in jobs("haul") if j["taker"] is None]
info(len(open_hauls), "haul jobs open")
```

## See also

[`take_job`](take_job.md), [`post_job`](post_job.md), [`colony`](colony.md), [Colonies, Jobs](../../features/colonies.md#jobs).
