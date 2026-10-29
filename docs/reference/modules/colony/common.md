# `colony/common`

What the default colony scripts share: finding the Colony Core and storing in and fetching from it. A module of the mod's default library: `from colony.common import core, store` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/colony/common.py`, written with [`colony`](../../index.md#colony-and-jobs), `put`, `take` and `container`.

The colony scripts themselves (`colony.lumberjack`, `colony.hauler`, `colony.builder`, `colony.farmer`, `colony.miner`) are scripts a bot runs, not modules to import; what each does and the data of their jobs is on [Default colony scripts](../../../features/colony-scripts.md).

## `core()`

The position of the colony's Colony Core, where members store what they bring in. A bot in no colony says so and the script stops ([`stop`](../../index.md#the-bot-and-its-output)). Done at once.

## `in_colony(p)`

Whether the position `p` lies within the colony's radius of its core; `False` for a bot in no colony. Done at once.

## `store(item)`

Puts all the bot carries of `item` (an item id or a [selector](../../types/selectors.md)) into the core.

- **Returns** how many went in: 0 when the bot carries none, or the core took none.
- **Pauses** yes, while it walks to the core and puts.

## `fetch(item, n)`

Takes items from the core until the bot carries `n` of `item` (an item id or a selector such as `"#minecraft:planks"`), as far as the core has them.

- **Returns** whether the bot carries `n` now.
- **Pauses** yes, while it walks to the core and takes; not when the bot carries enough already or the core has none.

## Example

Fetch a hoe from the core, or ask for one:

```python
from colony.common import fetch, store

if not fetch("#minecraft:hoes", 1):
    post_job("haul", {"item": "#minecraft:hoes", "n": 1})
store("minecraft:wheat")
```

## See also

[Default colony scripts](../../../features/colony-scripts.md), [Colonies](../../../features/colonies.md), [`colony_memory`](../../types/memory.md), [Colony and jobs](../../index.md#colony-and-jobs).
