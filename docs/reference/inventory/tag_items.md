# `tag_items`

```text
tag_items(tag)
```

The items of an item tag, as the server's data packs have it now.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `tag` | str | | one item tag, such as `"#minecraft:logs"` or `"#antcraft:scaffold_blocks"`; without a namespace it is in `minecraft:` |

## Returns

A list of item ids, full with their namespace (`"minecraft:dirt"`), in the order the tag lists them; empty for a tag with no items.

## Pauses

No, it is done at once.

## Cost

20 instructions besides the call.

## Description

A script that needs the members of a tag one by one, to ask [`has`](has.md) of each or to [`scan`](../world/scan.md) for only the kinds the bot carries, reads them here instead of copying the tag's values, which go stale when a data pack changes the tag. To test one item against a tag, [`matches`](matches.md) is enough. It does not look at the bot or the world.

A tag the game does not know (`unknown item tag '#minecraft:no_such_tag'`), or anything but one tag, such as an item id or a list, stops the script with an error.

## Examples

The blocks the bot's walks pillar and bridge with that it carries:

```python
carried = [b for b in tag_items("#antcraft:scaffold_blocks") if has(b)]
```

## See also

[selectors](../types/selectors.md), [`matches`](matches.md), [`has`](has.md), [`goto`](../movement/goto.md).
