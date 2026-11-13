# Bootstrap a bot from nothing

An empty-handed bot gets a starting kit from what is around it: logs by hand, a crafting table, a wooden pickaxe, cobblestone, the stone tools, a furnace, charcoal and torches. Tools in a nearby chest are borrowed instead of made, and given back at the end. Start it near a few trees and some stone:

```text
/antcraft bot run jobs.bootstrap
/antcraft bot run jobs.bootstrap 8
```

The argument is the radius it looks for trees, stone and chests in (32 by default). The job is one of the mod's default scripts, in `config/antcraft/scripts/jobs/bootstrap.py`:

```python
import lib.bootstrap
from lib.borrow import give_back

radius = int(ARGS[0]) if ARGS else 32
try:
    r = lib.bootstrap.kit(radius=radius)
    say("kit ready" if r["ok"] else f"no kit: {r['reason']}, missing {r['missing']}")
finally:
    give_back()
```

## How it works

- [`lib.bootstrap.kit`](../reference/modules/lib/bootstrap.md) gets each item of `KIT` in turn through the resolver, [`lib.get`](../reference/modules/lib/get.md), which takes what the bot carries, then the chests, then gathers and crafts the rest. The first tool, a stone pickaxe, needs cobblestone, which needs a pickaxe: the resolver makes a wooden one from logs it fells by hand first.
- A tool of the kit is any tool of its kind and at least its tier: an iron pickaxe in a chest does for the stone pickaxe, so it is [borrowed](../reference/modules/lib/borrow.md) and used to dig the cobblestone the other tools need.
- Torches burn coal. Where no coal ore is in sight and no chest holds coal, the resolver smelts charcoal from logs instead of digging for it.
- [`give_back()`](../reference/modules/lib/borrow.md#give_backradius16) puts what was borrowed back in its chest, worn. It is in `finally`, so it runs also when the kit fails or the script stops with an error (see [Errors](../language/errors.md)).
- A job of your own starts the same way: `lib.bootstrap.kit()`, or `kit(MINER)` with a list of `(item, n)` of its own, then its work, then `give_back()`.

The job runs as it is in `BootstrapGameTest`: an empty bot by four small oaks and a wall of stone ends with the whole kit, and with an iron pickaxe in a chest it uses that one and puts it back.
