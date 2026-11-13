# `lib/tools`

The tools the bot carries: which is best, how long they last, and which pickaxe a block needs. A module of the mod's default library: `import lib.tools` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/tools.py`, and a skill: `hold` is a doer, the rest are finders that take no ticks (see [Writing library functions](../../index.md#writing-library-functions)).

A `kind` is `"pickaxe"`, `"axe"`, `"shovel"`, `"hoe"` or `"sword"`; its tools are those of the item tag `#minecraft:<kind>s`. Tools rank by the tier in their id, best first: netherite, diamond, iron, stone, golden, wooden (`TIERS`); a tool of another tier (from a mod) ranks below these.

## `uses_left(stack)`

- **Returns** how many more blocks a tool [`Stack`](../../types/stack.md) breaks before it is used up: `max_damage - damage`. A fresh stone axe gives 131.
- **Pauses** no.

## `best(kind)`

- **Returns** the best tool of `kind` the bot carries, held one included, as a [`Stack`](../../types/stack.md); `None` where it carries none. With a stone and an iron pickaxe it gives the iron one.
- **Pauses** no.

## `enough(kind, uses)`

- **Returns** whether the tools of `kind` the bot carries together last `uses` more blocks: the sum of their `uses_left`. A fresh stone axe lasts 50 blocks; one at damage 100 does not.
- **Pauses** no.

## `tier_for(block_id)`

- **Returns** the lowest pickaxe tier that drops `block_id` (`"diamond_ore"` or `"minecraft:diamond_ore"`): `"stone"` for iron, copper and lapis ore, `"iron"` for gold, redstone, emerald and diamond ore, `"diamond"` for obsidian and ancient debris (and the blocks made of each), `"wooden"` for any other block.
- **Pauses** no.

It reads a table of the vanilla blocks of the game's `needs_*_tool` tags (`NEEDS`), not the tags, so a modded block that needs a tier gives `"wooden"`; add it to `lib.tools.NEEDS` in your script.

## `hold(kind)`

- **Does** [`equip`](../../inventory/equip.md) the best tool of `kind` the bot carries, by `TIERS`; one of no listed tier only where it carries none of these.
- **Returns** a [`result()`](result.md) dict, always `ok`.
- **Pauses** no more than `equip`.

[`lib/craft`](craft.md) imports it, so `lib.craft.hold(kind)` keeps working.

## Example

A lumberjack that makes sure its axes last the job before it starts:

```python
import lib.tools
import lib.craft

if not lib.tools.enough("axe", 64):
    lib.craft.ensure_tool("axe", upgrade=True)
lib.tools.hold("axe")
print("pickaxe for diamonds:", lib.tools.tier_for("diamond_ore"))
```

## See also

[`lib/cost`](cost.md), [`lib/craft`](craft.md), [`Stack`](../../types/stack.md), [`equip`](../../inventory/equip.md).
