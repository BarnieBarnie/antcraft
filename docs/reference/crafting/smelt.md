# `smelt`

```text
smelt(item, n=1, furnace=None, fuel=None)
```

Smelts carried items in a furnace, blast furnace or smoker, waits by it and takes the output.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `item` | [selector](../types/selectors.md) | | the carried input, such as `"raw_iron"` or `"#minecraft:logs"` |
| `n` | int | `1` | how many to load, at most a stack (64 for most items) |
| `furnace` | [`Pos`](../types/pos.md) or tuple | `None` | the furnace, blast furnace or smoker to walk to; `None` uses one within reach that cooks `item` |
| `fuel` | [selector](../types/selectors.md) | `None` | what to burn; `None` burns the best fuel carried: coal, then charcoal, then logs |

## Returns

A [`Result`](../types/result.md); its `value` is how many items it took out of the furnace.

## Pauses

Yes: it walks to `furnace` first, as [`goto`](../index.md#movement) does, if it is not within 4 blocks of the bot's eyes, then waits by the furnace until the input is smelted: about 10 seconds an item, 5 in a blast furnace or smoker.

## Cost

60 instructions besides the call. A walk to `furnace` plans its path as `goto` does.

## Description

It loads up to `n` of one carried item `item` selects (fewer if it carries fewer, or the input slot already holds some of it) into the input slot. When `item` selects several carried items, without `furnace` it takes the first one that a station within reach cooks; with `furnace`, the first one that station cooks. Without `furnace`, it looks only within 4 blocks of the bot's eyes where it stands.

It puts in one fuel item at a time, when the fire goes out and the fuel slot is empty, so it burns no more than it needs. Once the input slot is empty it takes the output into its inventory. When it gives up, it takes back what is left of the input along with the output made so far.

An `n` below 1, a malformed selector, or a name or tag the game does not know stops the script with an error.

## Examples

```python
r = smelt("raw_iron", 8, furnace=memory["furnace"])
print("iron ingots:", r.value)
```

```python
smelt("#minecraft:logs", 4, fuel="#minecraft:planks")
```

## Fails with

| Reason | When |
|---|---|
| `no_recipe` | no furnace, blast furnace or smoker cooks `item`, or the one at `furnace` does not, such as ore in a smoker |
| `missing_ingredients` | none of `item` carried, or no fuel to burn when the fire goes out |
| `no_furnace` | no furnace, blast furnace or smoker at `furnace`, or none within reach that cooks `item` |
| `blocked` | the furnace holds another input or output, or its input slot is full |
| `no_space` | the output does not fit and stays in the furnace; `value` is how many it took |
| `timeout` | the furnace made nothing for 1200 ticks (a minute), or the walk got stuck |
| `protected`, `out_of_reach`, `not_loaded` | at `furnace`, as for [`use`](../blocks/use.md) |
| `wrong_dimension` | `furnace` is in another dimension than the bot's |

## See also

[`craft`](craft.md), [`take`](take.md), [`container`](container.md).
