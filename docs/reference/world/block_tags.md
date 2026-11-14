# `block_tags`

```text
block_tags(block)
```

The tags of a block, by its id, as the server's data packs have it now.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `block` | str | | a block id, such as `"minecraft:iron_ore"` or a modded ore; without a namespace it is in `minecraft:` |

## Returns

A sorted list of the block tags of the block's default state, each with its `#` (`"#minecraft:needs_stone_tool"`); empty for an id the game knows no block by.

## Pauses

No, it is done at once.

## Cost

100 instructions besides the call, about its 2 µs (measured with `SenseCostGameTest`, #589).

## Description

The tags [`block(pos).tags`](../types/block.md) gives, for a block named instead of standing somewhere, so a script can tell what a block is (a modded ore's `#minecraft:needs_iron_tool`) before it finds one. It does not look at the bot or the world. [`lib.tools.tier_for`](../modules/lib/tools.md) reads it for a block id it has no tier for.

## Examples

```python
if "#minecraft:needs_iron_tool" in block_tags("minecraft:diamond_ore"):
    print("diamond ore needs an iron pickaxe")
```

## See also

[`block`](block.md), [`tag_items`](../inventory/tag_items.md), [selectors](../types/selectors.md).
