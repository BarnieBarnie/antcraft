# `build`

```text
build(blueprint, at, turn=0, mirror=False, job=None)
```

Builds a blueprint from the blocks the bot carries.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `blueprint` | str | | the blueprint's id, such as `"antcraft:starter_house"` |
| `at` | [`Pos`](../types/pos.md) or tuple | | where the blueprint's lowest corner goes |
| `turn` | int | `0` | quarters to turn it clockwise; any int, taken modulo 4 |
| `mirror` | bool | `False` | whether to mirror it left to right, before turning |
| `job` | int | `None` | the id of the job taken off the job board that the build is for (see Description) |

## Returns

A [`Result`](../types/result.md); its `value` is how many blocks it placed, also when it fails.

## Pauses

Yes, until the blueprint stands and the bot's scaffold is taken down, or the build stops short. With an id the server has no blueprint for, it fails at once.

## Cost

One instruction, the call itself. Its walks plan paths as [`goto`](../movement/goto.md) does.

## Description

The blueprint stands as the Blueprint item's ghost shows it with the same turn and mirror. Layer by layer from the bottom, the bot walks into reach of each block the world does not match yet, breaks what is in the way (drops go into the inventory) and places the block, in the blueprint's block state, from what it carries. A fluid is poured from its bucket once the blocks beside and under it stand. Blocks already right count as done, so `build` called again, such as after `missing_ingredients`, goes on where it stopped; so does a build resumed after a save.

A block it cannot reach from the ground it reaches standing on blocks already built, or by pillaring up with dirt it carries, never where the blueprint puts a block. Once the blueprint stands, or the build stops short of it, it takes that dirt down again (also after a save and load). A build cancelled before that (a reflex, `stop`) leaves the dirt with the bot for its next `build` to take down, wherever that build is; five minutes on, and every five minutes after, the bot takes down at once what of it is in its reach, into its inventory, so the next build walks only to the rest. Its walks break a block of the blueprint already in place only to get to a block no other walk gets to, and place it again after.

Another bot standing where a block goes steps aside when asked (see [Reflexes](../../features/bots.md#reflexes)); its own action ends with `interrupted`. An idle bot keeps off while the building bot holds the job `job`, or, without `job`, while it holds any `"build"` job and until 10 seconds after its last build. The building bot steps off a spot itself. A player or mob gets two seconds to go.

[`blueprint`](blueprint.md) tells a script beforehand which materials to fetch.

## Examples

```python
at = colony()["core"] + (4, -1, 0)
r = build("antcraft:farm", at, turn=1)
if not r and r.reason == "missing_ingredients":
    say("placed " + str(r.value) + " blocks, need more")
```

## Fails with

| Reason | When |
|---|---|
| `no_blueprint` | there is no blueprint with that id, or its structure is missing |
| `missing_ingredients` | the bot carries no block for the next step; `value` is how many it placed |
| `protected` | a block of it may not be changed: outside the build height or border, or under spawn protection |
| `blocked` | a player or mob still stands where a block goes after two seconds, a fluid is in the way, or the bot could not walk into reach, such as a block too high with no dirt to pillar up with |
| `not_loaded` | a chunk the blueprint is in is not loaded and the bot could not get to it |
| `wrong_dimension` | `at` is in another dimension than the bot's |
| `timeout` | a walk got stuck |

## See also

[`blueprint`](blueprint.md), [Blueprints](../../features/blueprints.md), [`colony.builder`](../../features/colony-scripts.md), [`place`](../blocks/place.md).
