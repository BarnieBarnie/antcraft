# `lib/food`

Keeping a bot fed, now that bots get [hungry](../../../features/bots.md#hunger). A module of the mod's default library: `import lib.food`, then call `lib.food.upkeep()` once a round of a long loop (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/food.py`, written with [`food`](../../bot/food.md), [`eat`](../../inventory/eat.md), [`lib/stock`](stock.md) and [`lib/get`](get.md); read it to see how.

The default jobs and colony scripts that run long call `upkeep()` each round: [`colony.lumberjack`, `colony.hauler`, `colony.builder`, `colony.farmer` and `colony.miner`](../../../features/colony-scripts.md), [`jobs.wood_farm`](../../../cookbook/wood-farm.md) (so `jobs.forester`), [`jobs.farm`](../../../cookbook/farm.md) and [`jobs.strip_mine`](../../../cookbook/strip-mine.md), before each branch; [`lib.bootstrap.kit`](bootstrap.md#kititemskit-radius32) before each item, so every job that gets a kit (`jobs.bootstrap`, `jobs.settle`); and `idle.py` every round, so an idle bot eats too. So a colony needs food: a farmer's wheat in the Colony Core is bread for the others. With `botHunger = false` (config) food stays at 20 and `upkeep` does nothing.

## `upkeep(radius=32)`

Call it once a round of a loop that runs long. At `LOW` food (14) or under, carrying no food of `FOODS`, it gets some with `ensure_food(STOCK, radius)`; where that finds none it warns (`hungry with no food in the colony core: ...` in a colony) and does not try again for `RETRY` ticks (1200). Then it eats as `eat_if_hungry`.

- **Returns** whether the bot ate.
- **Pauses** only while it gets food or eats; a fed bot costs a sense or two.

## `eat_if_hungry()`

Eats the best food of `FOODS` the bot carries, one after another, while none of it would be wasted (20 less [`food()`](../../bot/food.md) is at least the food's points), or while the bot is hurt and under `HEAL` food (18), the level it heals from.

- **Returns** whether it ate.
- **Pauses** 32 ticks a food eaten, as [`eat`](../../inventory/eat.md).

## `ensure_food(n=STOCK, radius=32)`

Makes the bot carry at least `n` food of `FOODS` (`STOCK` is 4). It first takes food from the chests of [`lib.stock.chests(radius)`](stock.md#chestsradius32), best first: the Colony Core's plainly, others' borrowed and kept ([`lib.borrow.keep`](borrow.md)), so `give_back` does not take food back. Short of that, it makes food with [`lib.get.get`](get.md), the first of `MADE` it can: bread from wheat, a baked potato, cooked meat or fish, smelting with a furnace as `get` does. Bread comes one at a time; for a smelted item it gets one of the raw item first where it carries none, then cooks all the raw it carries in one smelt, as a kill drops more than one: three raw beef take some 700 ticks, not 920 (#741, `FoodGameTest` `ensureFoodCooksTheRawBeefCarriedInOneSmelt`). Raw meat comes from the chests or, where none holds any, from hunting the animals within `radius` (#719, [`SOURCES`](get.md#sources)): a hungry bot with a sword, a furnace, a pickaxe to take it back and coal kills a cow, cooks its beef and eats it. A fed bot never loads `lib.get`.

- **Returns** a [`result()`](result.md): `ok`, or `no_item` with `missing` `{"food": k}`, how much food it is still short, keeping what it got.
- **Pauses** yes, while it walks to chests, gathers, crafts and smelts.

## `FOODS`, `MADE`, `MEALS`

`FOODS` is the food a bot eats, best first, as `(item, food points)`: the game's foods, [`foods()`](../../inventory/foods.md), so modded ones too (#722), but those of `LEFT_OUT` (suspicious stew). The items of `FIRST` come first in its order: golden carrots, cooked meat and fish, beetroot soup, baked potatoes, bread; then the rest, most points first: rabbit stew, pumpkin pie, mushroom stew, apples, carrots, raw meat, berries and the like. Eating a stew keeps the bowl. `foods()` leaves out what has an effect on eating: what poisons or hurts (raw chicken, rotten flesh, spider eyes, pufferfish, poisonous potatoes), chorus fruit, golden apples and honey. `MADE` is what `ensure_food` makes, in turn. `MEALS` are the items of `FOODS` worth 5 food points or more, the meals [`colony.farmer`](../../../features/colony-scripts.md) keeps in the core, `STOCK` for each member (#704).

```python
import lib.food

while True:
    lib.food.upkeep()
    # ... one round of the job
    wait(200)
```

## See also

[Hunger](../../../features/bots.md#hunger), [`food`](../../bot/food.md), [`eat`](../../inventory/eat.md), [`lib/get`](get.md), [`lib/stock`](stock.md), [Modules & import](../../../language/modules.md#the-mods-library).
