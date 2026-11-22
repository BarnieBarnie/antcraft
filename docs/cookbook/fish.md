# Go fishing

The job `jobs/fish` fishes by a pond with a fishing rod until a player stops it, for food (cod, salmon and the rest) and loot such as saddles, name tags and enchanted books. It needs no farm or animals, only a rod, water and chests, or the planks or logs to make them (#962).

```text
/antcraft bot run jobs.fish
/antcraft bot run jobs.fish 10 63 12 16
```

The arguments are the water block to cast at `x y z` (by default open water within 16 blocks of the bot, as [`fish`](../reference/blocks/fish.md) chooses it) and how far to look for chests and water, in blocks (default 32). The bot sorts its catch into chests it places by the water itself (see [The depot](#the-depot)).

Without `x y z`, a bot that finds no open water within 16 blocks looks for the nearest open water (a water source with air over it) within that radius, walks to it and fishes there (#994). It keeps that spot in `memory["fish"]`, so later rounds, and the job run again, go back to it; where the spot is no water any more, it looks again. With no open water in reach it warns `fish: no water` and stops.

## The rounds

Each round:

| Step | How |
|---|---|
| eat | [`lib.food.upkeep`](../reference/modules/lib/food.md): when hungry the bot eats, taking food from the chests, the fish it stored too, and cooking raw fish where it can |
| rod | carrying no fishing rod, it gets one with [`lib.get.get`](../reference/modules/lib/get.md): from a chest, or crafted from 3 sticks and 2 string; where it cannot, it warns `fish: no fishing rod, and none to get` and stops |
| fish | it catches one item with [`fish`](../reference/blocks/fish.md), at the water farther off where `fish` finds none |
| store | what it caught goes into its depot, a chest per kind (below); where no chest has room and it cannot get another, it warns `fish: no chest has room for [...] and none to get: <reason>` and stops |

A rod that breaks (`rod_broke`) only starts the next round, which gets a new one. Any other failure of `fish` it warns about and stops: `no_water` as `fish: no water`.

## The depot

The bot keeps its catch in a [`lib.depot`](../reference/modules/lib/depot.md) named `fish` (#995): a row of chests that starts one block behind where it stood for its first catch, away from the water, and runs along the shore. Each kind of catch has its own chests:

| Category | What |
|---|---|
| `fish` | `#minecraft:fishes`: cod, salmon, tropical fish and pufferfish |
| `books` | `minecraft:enchanted_book` |
| `rods` | `minecraft:fishing_rod`, those caught |
| `other` | the rest: junk such as sticks and leather, and treasure such as saddles and name tags |

The first catch of a kind places a new chest at the next slot of the row for it; so does a catch whose chests are full. The chest comes from [`lib.get`](../reference/modules/lib/get.md): carried, from a chest within the radius, or crafted from planks or logs. The bot stores all it carries but one fishing rod, the one it fishes with, its crafting table, and the tools, weapons and food that no category takes (`lib.storage.KEEP_KINDS`): fish go to the `fish` chests, where [`lib.food.upkeep`](../reference/modules/lib/food.md) takes them back when it is hungry.

## In a script

```python
import jobs.fish
jobs.fish.run(Pos(10, 63, 12))
```

`run(at=None, radius=32)` never returns but when it stops, giving the result of the `fish` or `get` that stopped it.
