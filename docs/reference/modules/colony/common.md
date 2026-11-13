# `colony/common`

What the default colony scripts share: finding the Colony Core and storing in and fetching from it. A module of the mod's default library: `from colony.common import core, store` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/colony/common.py`, written with [`colony`](../../colony/colony.md), `put` and [`lib/stock`](../lib/stock.md). The colony scripts take from the core with `lib.stock.take_up_to(item, n, chests=[core()])`, not borrowed.

Importing it keeps the library within the colony: it sets [`lib.find.WHERE`](../lib/find.md#where) to `in_colony`, so [`lib/get`](../lib/get.md), [`lib/stock`](../lib/stock.md), [`lib/trees`](../lib/trees.md) and [`lib/dig`](../lib/dig.md) neither take from a chest nor gather a block outside the colony's radius of its core.

The colony scripts themselves (`colony.lumberjack`, `colony.hauler`, `colony.builder`, `colony.farmer`, `colony.miner`) are scripts a bot runs, not modules to import; what each does and the data of their jobs is on [Default colony scripts](../../../features/colony-scripts.md).

## `core()`

The position of the colony's Colony Core, where members store what they bring in. A bot in no colony says so and the script stops ([`stop`](../../bot/stop.md)). Done at once.

## `in_colony(p)`

Whether the position `p` lies within the colony's radius of its core; `False` for a bot in no colony. Done at once.

## `store(item)`

Puts all the bot carries of `item` (an item id or a [selector](../../types/selectors.md)) into the core.

- **Returns** how many went in: 0 when the bot carries none, or the core took none.
- **Pauses** yes, while it walks to the core and puts.

## `fetch(item, n)`

Kept for old scripts: whether [`lib.stock.take_up_to(item, n, chests=[core()])`](../lib/stock.md#take_up_toitem-n-radius32-chestsnone) got the bot `n` of `item`, taking from the core as far as it has them.

- **Returns** whether the bot carries `n` now.
- **Pauses** as `take_up_to`.

## Example

Take a hoe from the core, or ask for one:

```python
from colony.common import core, store
from lib.stock import take_up_to

if not take_up_to("#minecraft:hoes", 1, chests=[core()])["ok"]:
    post_job("haul", {"item": "#minecraft:hoes", "n": 1})
store("minecraft:wheat")
```

## See also

[Default colony scripts](../../../features/colony-scripts.md), [Colonies](../../../features/colonies.md), [`colony_memory`](../../types/memory.md), [Colony and jobs](../../index.md#colony-and-jobs).
