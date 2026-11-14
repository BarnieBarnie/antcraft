# Get anything from nothing

The job `jobs/get` gets any item from what is around, the tools to get it included, with the resolver [`lib/get`](../reference/modules/lib/get.md) alone. An empty-handed bot by a tree, some stone and an exposed iron vein gets an iron pickaxe:

```text
/antcraft bot run jobs.get iron_pickaxe
```

A script in a folder runs by its dotted name: `jobs.get`, not `jobs/get`. The arguments are the item, how many (default 1) and how far to look, in blocks (default 32): `/antcraft bot run jobs.get torch 16 48`. The bot says `got 1 iron_pickaxe`, or why not, such as `no feather: no_recipe {'minecraft:feather': 1}`.

The job, `config/antcraft/scripts/jobs/get.py`:

```python
from lib.get import get, key
from lib.borrow import give_back, keep

def run(item, n=1, radius=32):
    """Gets n of item, keeps them, gives back what it borrowed on the way and says how it went. Gives get's result()."""
    try:
        r = get(item, n, radius)
    finally:
        # The n asked for are the job's: kept, so give_back does not take them back.
        keep(key(item), n)
        give_back()
    say(f"got {n} {item}" if r["ok"] else f"no {item}: {r['reason']} {r['missing']}")
    return r


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    if not args:
        say("usage: jobs.get <item> [n] [radius]")
        stop()
    run(args[0], int(args[1]) if len(args) > 1 else 1, int(args[2]) if len(args) > 2 else 32)
```

## How it works

- [`get(item, n, radius)`](../reference/modules/lib/get.md#getitem-n1-radius32) does all the work. For an iron pickaxe it plans the recipe with [`lib.craft.plan`](../reference/modules/lib/craft.md): three iron ingots, two sticks, a crafting table and a furnace. Then it gets each raw item. The logs come by hand. The cobblestone needs a pickaxe, so `ensure_tool` makes a wooden one first. The raw iron needs a stone pickaxe, so it makes one of those from the cobblestone. It then digs the raw iron with [`lib.dig.ore`](../reference/modules/lib/dig.md). Last, [`craft_here`](../reference/modules/lib/craft.md) crafts the furnace, smelts the iron and makes the pickaxe. The planks left over are the furnace's fuel (see [`needs`](../reference/crafting/needs.md)).
- `get` takes from a chest within `radius` before it makes anything. What it takes it borrows, through [`lib/borrow`](../reference/modules/lib/borrow.md). Where `get` fails it gives back what it borrowed itself. [`give_back()`](../reference/modules/lib/borrow.md#give_backradius16-since0) in `finally` returns the rest, also when the script stops with an error (see [Catching errors](../language/errors.md#catching-errors)). A tool goes back worn. The `n` asked for are the job's result, so [`keep(key(item), n)`](../reference/modules/lib/borrow.md#keepitem-nnone) strikes `n` of them, less what the bot owned of the item already, off the borrowed list first and the bot keeps them; what it borrowed of the item beyond `n` goes back. [`key`](../reference/modules/lib/get.md#keyitem) turns a log into `#minecraft:logs`, and `keep` matches a selector to the items it selects, so `jobs.get #minecraft:planks 2` keeps 2 borrowed oak planks.
- An item that no source gathers and no recipe makes fails with `no_recipe`, and `missing` names it and how many. A failed step gives its own reason, such as `no_stone` when the stone within `radius` runs out. [`SOURCES`](../reference/modules/lib/get.md) lists what `get` can gather. Add your own there in a script that imports `lib.get`.

`GetJobGameTest` runs the job as it is: an iron pickaxe from nothing, feathers that fail with `no_recipe`, an iron pickaxe taken from a chest and kept, and 2 of 5 borrowed planks kept and the other 3 given back.
