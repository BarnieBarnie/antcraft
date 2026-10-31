# `build_placement`

```text
build_placement(name, job=None)
```

Builds a Litematica placement the bot's owner sent from their client, where Litematica shows it, from the blocks the bot carries.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `name` | str | | the placement's name, as the owner's client sent it |
| `job` | int | `None` | the id of the job taken off the job board that the build is for, as for [`build`](build.md) |

## Returns

A [`Result`](../types/result.md); its `value` is how many blocks it placed, also when it fails.

## Pauses

Yes, as [`build`](build.md) does. With a name the owner sent no placement under, it fails at once.

## Cost

One instruction, the call itself. Its walks plan paths as [`goto`](../movement/goto.md) does.

## Description

A client sends the server a schematic with its origin, rotation and mirror (see [Litematica placements](../../features/blueprints.md)); the server keeps it under its name for that player. `build_placement` builds it as [`build`](build.md) builds a blueprint, each block at the origin plus its position in the schematic, mirrored, then rotated, as Litematica places it, in the dimension the player sent it from.

Only the placements of the bot's owner: a bot owned by another player, or by no one, does not see them. A placement is kept until its player leaves or the server stops.

## Examples

```python
r = build_placement("house")
if not r and r.reason == "missing_ingredients":
    say("placed " + str(r.value) + " blocks, need more")
```

## Fails with

| Reason | When |
|---|---|
| `no_blueprint` | the bot's owner sent no placement by that name (or the bot has no owner) |
| `wrong_dimension` | the placement is in another dimension than the bot's |

Otherwise as [`build`](build.md): `missing_ingredients`, `protected`, `blocked`, `not_loaded`, `timeout`.

## See also

[`build`](build.md), [`blueprint`](blueprint.md).
