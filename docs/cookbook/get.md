# Get anything from nothing

The job `jobs/get` gets any item from what is around, the tools to get it included, with the resolver [`lib/get`](../reference/modules/lib/get.md) alone. An empty-handed bot by a tree, some stone and an exposed iron vein gets an iron pickaxe:

```text
/antcraft bot run jobs.get iron_pickaxe
```

A script in a folder runs by its dotted name: `jobs.get`, not `jobs/get`. The arguments are the item, how many (default 1) and how far to look, in blocks (default 32): `/antcraft bot run jobs.get torch 16 48`. The bot says `got 1 iron_pickaxe`, or why not, such as `no feather: no_recipe {'minecraft:feather': 1}`.

The job, `config/antcraft/scripts/jobs/get.py`:

```python
from lib.get import get, key
from lib.borrow import borrowed, give_back, keep

if not ARGS:
    say("usage: jobs.get <item> [n] [radius]")
    stop()
item = ARGS[0]
n = int(ARGS[1]) if len(ARGS) > 1 else 1
radius = int(ARGS[2]) if len(ARGS) > 2 else 32
try:
    r = get(item, n, radius)
finally:
    # The item asked for is the job's: kept, so give_back does not take it back.
    for b in borrowed():
        if key(b["item"]) == key(item):
            keep(b["item"])
    give_back()
say(f"got {n} {item}" if r["ok"] else f"no {item}: {r['reason']} {r['missing']}")
```

## How it works

- [`get(item, n, radius)`](../reference/modules/lib/get.md#getitem-n1-radius32) does all the work. For an iron pickaxe it plans the recipe with [`lib.craft.plan`](../reference/modules/lib/craft.md): three iron ingots, two sticks, a crafting table and a furnace. Then it gets each raw item. The logs come by hand. The cobblestone needs a pickaxe, so `ensure_tool` makes a wooden one first. The raw iron needs a stone pickaxe, so it makes one of those from the cobblestone. It then digs the raw iron with [`lib.dig.ore`](../reference/modules/lib/dig.md). Last, [`craft_here`](../reference/modules/lib/craft.md) crafts the furnace, smelts the iron and makes the pickaxe. The planks left over are the furnace's fuel (see [`needs`](../reference/crafting/needs.md)).
- `get` takes from a chest within `radius` before it makes anything. What it takes it borrows, through [`lib/borrow`](../reference/modules/lib/borrow.md). [`give_back()`](../reference/modules/lib/borrow.md#give_backradius16) in `finally` returns it, also when `get` fails or the script stops with an error (see [Catching errors](../language/errors.md#catching-errors)). A tool goes back worn. The item asked for is the job's result, so [`keep`](../reference/modules/lib/borrow.md#keepitem-nnone) strikes it off the borrowed list first and the bot keeps it.
- An item that no source gathers and no recipe makes fails with `no_recipe`, and `missing` names it and how many. A failed step gives its own reason, such as `no_stone` when the stone within `radius` runs out. [`SOURCES`](../reference/modules/lib/get.md) lists what `get` can gather. Add your own there in a script that imports `lib.get`.

`GetJobGameTest` runs the job as it is: an iron pickaxe from nothing, feathers that fail with `no_recipe`, and an iron pickaxe taken from a chest and kept.
