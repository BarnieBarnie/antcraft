# `structure_tags`

```text
structure_tags(structure)
```

The tags of a structure, by its id, as the server's data packs have it now.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `structure` | str | | a structure id, such as `"minecraft:village_plains"` or one [`structures`](structures.md) gave; without a namespace it is in `minecraft:` |

## Returns

A sorted list of the structure's tags, each with its `#` (`"#minecraft:village"`); empty for an id the game knows no structure by.

## Pauses

No, it is done at once.

## Cost

15 instructions besides the call (it takes some 0.25 µs, measured with `SenseCostGameTest` as in #588).

## Description

What kind of structure it is, without listing every structure of that kind: `#minecraft:village` holds the plains, desert, savanna, snowy and taiga villages, modded ones too where their data packs add them. It does not look at the bot or the world. [`lib.explore.seen`](../modules/lib/explore.md#seenwhat) reads it for a `what` that starts with `#`.

## Examples

```python
for s in structures(64):
    if "#minecraft:village" in structure_tags(s["id"]):
        print("a village at", s["at"])
```

## See also

[`structures`](structures.md), [`biome_tags`](biome_tags.md), [`lib/explore`](../modules/lib/explore.md).
