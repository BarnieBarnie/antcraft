# Go fishing

The job `jobs/fish` fishes by a pond with a fishing rod until a player stops it, for food (cod, salmon and the rest) and loot such as saddles, name tags and enchanted books. It needs no farm or animals, only a rod, water and a chest (#962).

```text
/antcraft bot run jobs.fish
/antcraft bot run jobs.fish 10 63 12 16
```

The arguments are the water block to cast at `x y z` (by default open water within 16 blocks of the bot, as [`fish`](../reference/blocks/fish.md) chooses it) and how far to look for chests, in blocks (default 32). Put a chest by the water: the bot walks to it after each catch.

## The rounds

Each round:

| Step | How |
|---|---|
| eat | [`lib.food.upkeep`](../reference/modules/lib/food.md): when hungry the bot eats, taking food from the chests, the fish it stored too, and cooking raw fish where it can |
| rod | carrying no fishing rod, it gets one with [`lib.get.get`](../reference/modules/lib/get.md): from a chest, or crafted from 3 sticks and 2 string; where it cannot, it warns `fish: no fishing rod, and none to get` and stops |
| fish | it catches one item with [`fish`](../reference/blocks/fish.md) |
| store | what it caught goes into the nearest chests with room ([`lib.stock.chests`](../reference/modules/lib/stock.md)); where none has room, it warns and stops |

A rod that breaks (`rod_broke`) only starts the next round, which gets a new one. Any other failure of `fish`, such as `no_water`, it warns about and stops.

## In a script

```python
import jobs.fish
jobs.fish.run(Pos(10, 63, 12))
```

`run(at=None, radius=32)` never returns but when it stops, giving the result of the `fish` or `get` that stopped it.
