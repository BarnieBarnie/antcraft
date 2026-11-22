# Keep the colony store

Gathering bots store what they gather in a depot by their farm or mine, and keep gathering. A storekeeper walks round those depots and moves their output into one sorted store the player can take from: a grid of chests, one category each, that grows a chest whenever a category fills. The mod bundles it as `jobs/storekeeper.py`; start it with `/antcraft bot run jobs.storekeeper 100 64 100 east` for a store from (100, 64, 100) going east, or with no position for where the bot stands.

```python
import jobs.storekeeper

jobs.storekeeper.run(Pos(100, 64, 100), "east")
```

## How it works

- The store is a [`lib/depot`](../reference/modules/lib/depot.md) named `storage`, rows of 8 chests (`WIDTH`), with the categories logs, planks, stone, ores, crops, food, mob drops and other (`CATEGORIES`). It is published as the colony store ([`publish_store`](../reference/modules/lib/depot.md)), so every member of the colony finds it in `colony_memory["storage"]`.
- The sources are, by default, every chest of every depot a member published as a source, such as each farm's depot ([`published`](../reference/modules/lib/depot.md)), read again each round, so a new farm is picked up without restarting the storekeeper. Pass `sources`, a list of chest positions, to haul from those instead. It never takes from its own store.
- Each round it stores what it carries ([`lib.depot.store`](../reference/modules/lib/depot.md), keeping a few meals of its own), then [`take`](../reference/crafting/take.md)s from each source chest what the store sorts until its inventory is full. Tools, weapons and torches that no category takes are left where they are. The next round stores the load and comes back for the rest.
- A source chest keeps what its gatherer takes back out: by default the seeds, saplings, torches and bone meal (`KEEP`). Pass `keep`, a dict of a source depot's name or a chest's position to a keep list in `store`'s format, to change that for one source: `{"wheat_farm": ["minecraft:wheat_seeds", ("minecraft:wheat", 16)]}` leaves all the seeds and 16 wheat.
- With nothing to haul it eats ([`lib.food.upkeep`](../reference/modules/lib/food.md)) and waits 100 ticks.
- When the store cannot grow, as no chest can be got for a full category, it warns once and hauls nothing until a later `store` succeeds, so nothing it takes is dropped.

`StorekeeperGameTest` runs the bundled job: a source chest of logs and cobblestone sorted into two chests of the store, a wood farm's published depot hauled with its saplings left behind, and a chest of 27 stacks taken in more than one load with nothing dropped.

## See also

- [`lib/depot`](../reference/modules/lib/depot.md), [`take`](../reference/crafting/take.md), [`container`](../reference/crafting/container.md), [`lib/food`](../reference/modules/lib/food.md)
- [Store and sort](store.md), which puts away what one bot carries
- [Grow a wood farm](wood-farm.md), a gatherer whose depot a storekeeper empties
