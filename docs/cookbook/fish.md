# Go fishing

The job `jobs/fish` fishes by a pond with a fishing rod until a player stops it, for food (cod, salmon and the rest) and loot such as saddles, name tags and enchanted books. It needs no farm or animals, only a rod, water and chests, or the planks or logs to make them (#962).

```text
/antcraft bot run jobs.fish
/antcraft bot run jobs.fish 10 63 12 16
```

The arguments are the water block to cast at `x y z` (by default open water within 16 blocks of the bot, as [`fish`](../reference/blocks/fish.md) chooses it) and how far to look for chests and water, in blocks (default 32, and at most 32, the most a [`scan`](../reference/world/scan.md) reads: a larger radius counts as 32, #998). The bot sorts its catch into chests it places by the water itself (see [The depot](#the-depot)).

After the other arguments, `storage x y z` gives the colony store: the job takes its tools and materials from the chests within 8 blocks of `x y z` first, however far and wherever they are ([`lib.stock.STORAGE`](../reference/modules/lib/stock.md#storage)). A colony member without it uses the store its colony published ([`lib.depot.publish_store`](../reference/modules/lib/depot.md)).

Without `x y z`, a bot that finds no open water within 16 blocks looks for the nearest open water (a water source with air over it) within that radius, walks to it and fishes there (#994). Like `fish`, it casts where open water lies all around, so a cast a little off still lands in water: from the nearest open water it steps to the neighbour with the most open water around it while that has more, so off a pond's edge into its middle (#998). It keeps that spot in `memory["fish"]`, so later rounds, and the job run again, go back to it; where the spot is no water any more, it looks again. With no open water in reach it warns `fish: no water` and stops.

## The rounds

Each round:

| Step | How |
|---|---|
| eat | [`lib.food.upkeep`](../reference/modules/lib/food.md): when hungry the bot eats, taking food from the chests, the fish it stored too, and cooking raw fish where it can |
| rod | in its first round, and when its rod broke, it holds the best fishing rod it carries or a chest within the radius holds (below); carrying none, it gets one with [`lib.get.get`](../reference/modules/lib/get.md): from a chest, or crafted from 3 sticks and 2 string, the sticks and a crafting table crafted from planks where it has none; where it cannot, it warns `fish: no fishing rod, and none to get` and stops |
| fish | it catches one item with [`fish`](../reference/blocks/fish.md), at the water farther off where `fish` finds none |
| store | what it caught goes into its depot, a chest per kind (below); where no chest has room and it cannot get another, it warns `fish: no chest has room for [...] and none to get: <reason>` and stops |

A rod that breaks, on the last catch or before it (`rod_broke`), only starts the next round, which holds the next best rod carried or stored, else gets a new one (#999). Any other failure of `fish` it warns about and stops: `no_water` as `fish: no water`.

## The best rod

Rods are treasure, often enchanted, so the bot fishes with the best one it has (#996), by [`lib.enchant.best_rod`](../reference/modules/lib/enchant.md): Lure counts most, then Luck of the Sea and Mending, then Unbreaking; of equal rods, the one with more uses left. Where a chest within the radius holds a rod better than its own, the bot takes only that rod and holds it; the chest's worse rods stay where they are (#999). After each catch it holds the best rod it carries, so a better rod caught replaces the one it fished with, which goes to the `rods` chest with any worse rod caught.

## The depot

The bot keeps its catch in a [`lib.depot`](../reference/modules/lib/depot.md) named `fish` (#995): a row of chests that starts one block behind where it stood for its first catch, away from the water, and runs along the shore. Each kind of catch has its own chests:

| Category | What |
|---|---|
| `fish` | `#minecraft:fishes`: cod, salmon, tropical fish and pufferfish |
| `books` | `minecraft:enchanted_book` |
| `rods` | `minecraft:fishing_rod`, those caught |
| `other` | the rest: junk such as sticks and leather, and treasure such as saddles and name tags |

The first catch of a kind places a new chest at the next slot of the row for it; so does a catch whose chests are full. The chest comes from [`lib.get`](../reference/modules/lib/get.md): carried, from a chest within the radius, or crafted from planks or logs. The bot stores all it carries but one fishing rod, the best, which it holds, its crafting table, and the tools, weapons and food that no category takes (`lib.storage.KEEP_KINDS`): fish go to the `fish` chests, where [`lib.food.upkeep`](../reference/modules/lib/food.md) takes them back when it is hungry.

## In a script

```python
import jobs.fish
jobs.fish.run(Pos(10, 63, 12))
```

`run(at=None, radius=32)` never returns but when it stops, giving the result of the `fish` or `get` that stopped it.
