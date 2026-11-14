# `biome_tags`

```text
biome_tags(biome)
```

The tags of a biome, by its id, as the server's data packs have it now.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `biome` | str | | a biome id, such as `"minecraft:forest"` or one [`biome`](biome.md) gave; without a namespace it is in `minecraft:` |

## Returns

A sorted list of the biome's tags, each with its `#` (`"#minecraft:is_forest"`); empty for an id the game knows no biome by.

## Pauses

No, it is done at once.

## Cost

50 instructions besides the call (it takes some 1 µs, #588).

## Description

What kind of land a biome is, without listing every biome of that kind: `#minecraft:is_forest` holds the forest, birch forest, dark forest and the others, modded ones too where their data packs add them. It does not look at the bot or the world. [`lib.explore.seen`](../modules/lib/explore.md#seenwhat) reads it for a `what` that starts with `#`.

## Examples

```python
b = biome()
if b is not None and "#minecraft:is_forest" in biome_tags(b):
    print("in a forest:", b)
```

## See also

[`biome`](biome.md), [`block_tags`](block_tags.md), [`lib/explore`](../modules/lib/explore.md).
