# `colony`

```text
colony()
```

The bot's colony as a dict, or `None` for a bot in no colony.

## Returns

A dict of plain data, a snapshot taken at the call:

- `core`: the `Pos` of the Colony Core, with the core's dimension.
- `radius`: the colony's radius in blocks, an int.
- `members`: a list of the members' UUID strings.
- `owner`: the owning player's UUID string, or `None`.

## Pauses

No, it is done at once.

## Cost

35 instructions besides the call.

## Description

A member in another dimension than its core (it went through a portal, or respawned at a home elsewhere) stays a member: `colony()` works the same there, and `core.dimension` tells the script to [`travel`](../index.md#movement) first, as `put` and `take` at the core fail with `wrong_dimension` until it does.

The Colony Core stores items like a chest, so [`container`, `put` and `take`](../index.md#containers-and-crafting) work on `colony()["core"]`. What the members share goes in [`colony_memory`](../types/memory.md); the work they split goes on the job board ([`post_job`](post_job.md), [`take_job`](take_job.md)).

## Examples

```python
c = colony()
if c is None:
    say("not in a colony")
else:
    put(c["core"], "oak_log", 64)
```

## See also

[`colony_memory`](../types/memory.md), [`jobs`](jobs.md), [`post_job`](post_job.md), [Colonies](../../features/colonies.md).
