# `lib/enchant`

Picking the best of several enchantable items, such as the best fishing rod a bot carries (#992) or the best pickaxe (#1001). A module of the mod's default library: `import lib.enchant` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/enchant.py`, and a skill whose functions are all finders that take no ticks (see [Writing library functions](../../index.md#writing-library-functions)).

Each kind of item values its own enchantments, given as weights: a dict of enchantment id to weight, such as `ROD` or `PICKAXE`. The scoring is the same for every kind.

## `score(stack, weights)`

- **Returns** the sum over `weights` of weight × level / max level of the [`Stack`](../../types/stack.md)'s `enchantments`, so an item with every weighted enchantment at its max level scores highest, and one with none scores 0. The max levels are those of `MAX`; an enchantment of `weights` must be in it.
- **Pauses** no.

## `better(a, b, weights)`

- **Returns** whether Stack `a` beats Stack `b`: a higher `score`, or the same and more uses left ([`lib.tools.uses_left`](tools.md#uses_leftstack)). So a job can tell a caught rod beats the one it holds.
- **Pauses** no.

## `best(stacks, weights)`

- **Returns** the Stack of `stacks` that beats every other by `better`; `None` for an empty list.
- **Pauses** no.

## `best_rod(stacks)`

- **Returns** the best fishing rod of `stacks` by `ROD`, other items left out; `None` where there is none. `lib.enchant.best_rod(inventory())` gives the best rod the bot carries. A perfect rod (Lure III, Luck of the Sea III, Mending, Unbreaking III) beats every other, however worn; an enchanted rod beats a plain one; of two equal rods the one with more uses left wins (`ToolsGameTest` `bestRodPicksThePerfectRod`).
- **Pauses** no.

## `best_pickaxe(stacks, need_tier=None)`

- **Returns** the best pickaxe of `stacks` (the item tag `#minecraft:pickaxes`), other items left out; `None` where there is none. Tier ranks first, by [`lib.tools.TIERS`](tools.md), then `better` by `PICKAXE`, so a plain diamond pickaxe beats any iron one, and of iron ones Fortune III beats Efficiency V beats plain; of two equal pickaxes the one with more uses left wins. A pickaxe below `need_tier` (a `TIERS` name, such as `"iron"`) never wins, nor one of no listed tier (from a mod), so with none of that tier or better it gives `None` (`ToolsGameTest` `bestPickaxeRanksTierThenEnchantments`).
- **Pauses** no.

## `ROD`, `PICKAXE`, `MAX`

`ROD` is a fishing rod's weights: Lure 3 (it catches sooner), Luck of the Sea 2 (more treasure), Mending 2 and Unbreaking 1 (it lasts). `PICKAXE` is a pickaxe's: Fortune 3 (more ore), Efficiency 2 (it mines faster), Mending 2 and Unbreaking 1; Silk Touch counts nothing, as ore blocks still smelt. `MAX` is the max level of each enchantment the weights name (Lure, Luck of the Sea, Unbreaking and Fortune 3, Efficiency 5, Mending 1), listed by hand as a Stack gives only the level: weights for another kind of item add their enchantments to it.

## See also

[`Stack`](../../types/stack.md), [`inventory`](../../inventory/inventory.md), [`lib/tools`](tools.md), [`jobs.fish`](../../../cookbook/fish.md).
