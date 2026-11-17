# `lib/cellfarm`

The engine of a self-expanding farm (#826): it lays a farm of cells out by a corner, keeps a depot by it, builds a cell each time it can get what one takes, up to the cells that keep growing round its corner, and farms them round after round, moving the farm where it is run with a new corner. What a cell is, and how it is built, checked, harvested and tended, a spec says: a dict of hooks. The [wood farm](../../../cookbook/wood-farm.md) is its tree spec. A module of the mod's default library: `import lib.cellfarm` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/cellfarm.py`, a kit over [`lib/cells`](cells.md), [`lib/depot`](depot.md), [`lib/project`](project.md) and [`lib/terrain`](terrain.md) (see [The script library](../../../design/library.md#layers)).

A farm job calls [`run`](#runf-name-key-at-steps-radius32) with its spec and its steps, the last of which is [`farm`](#farmf-name-size-radius); steps before it, such as getting a kit and building the first cell with [`build`](#buildf-name-size-radius), are the job's own:

```python
import lib.cellfarm
from lib.project import Step
from lib.result import result

T = "minecraft:torch"
H = "minecraft:hay_block"

def build_cell(low, high):
    r = place(low.up(), T)
    return result() if r else result(r.reason, low.up())

def harvest(low, high, told):
    mine(low.up())
    pick_up(H)
    return result()

def tend(boxes, told):
    for b in boxes:
        if block(b[0].up()).is_air and count(T):
            place(b[0].up(), T)

f = {"size": (1, 1), "categories": {"hay": [H]}, "corners": "hay_farms", "keep": [T],
     "materials": lambda: {T: 1}, "build_cell": build_cell,
     "ripe": lambda low, high: block(low.up()).id == H,
     "harvest": harvest, "tend": tend, "most": lambda: 4}
lib.cellfarm.run(f, "hay_farm", "hay", None, [Step("farm", lib.cellfarm.farm, (f, "hay_farm", None, 32))])
```

## The spec

A dict. A cell's hooks take its box, `low` and `high` the corners of its floor (one block high, from [`lib/cells`](cells.md)); a hook that gives `result()` gives a [`lib/result`](result.md) dict.

| Key | Meaning |
|---|---|
| `size` | a cell's `(w, d)` along x and z |
| `categories` | the depot's categories, category to item ids and tags ([`lib.depot.depot`](depot.md)) |
| `corners` | the key of [`memory`](../../types/memory.md) where the farms' corners are kept, by `run`'s `key`, such as `"wood_farms"` |
| `keep` | what [`lib.depot.store`](depot.md) keeps on the bot: items, or `(item, n)` |
| `materials()` | item to count: what the bot carries to build one more cell ([`stocked`](#stockedf-radius)) |
| `build_cell(low, high)` | builds the cell, once `build` got its fill and tools; gives `result()` |
| `ripe(low, high)` | whether the cell has something to harvest |
| `harvest(low, high, told)` | harvests the cell, and replants it where the farm replants; gives `result()`. `told` is the round's dict for [`tell`](#telltold-what) |
| `tend(boxes, told)` | puts back what the cells `boxes` lack, such as a planting; a farm that never replants (a stem, a berry bush) does nothing here |
| `most()` | the cells at which the bot is busy; the farm grows to no more ([`limit`](#limitf-name-size)) |

Optional:

| Key | Default | Meaning |
|---|---|---|
| `gets(item, n, radius)` | [`lib.get.get`](get.md) | how the farm gets an item, for `stocked` and `build`'s fill |
| `fill` | `minecraft:dirt` | the item [`lib.terrain.needs`](terrain.md)' fill is got as |
| `height` | `1` | the blocks over the floor `build` counts the tools and fill to clear for |
| `tools(low, high)` | none | tools, kind to uses, `build` gets on top of `lib.terrain.needs`' |
| `spots(low, high)` | `[low, high]` | the blocks that must get random ticks for the cell to grow ([`ticking`](#tickingf-name)) |
| `loaded(low, high)` | both corners loaded | whether the bot can read the cell |
| `room()` | `1` | empty slots the bot wants before a harvest ([`lib.depot.make_room`](depot.md)) |
| `stock(boxes)` | none | gets what tending `boxes` takes, after the harvest and store |
| `chores(keep, told)` | none | harvests before the round's own, such as cells of another farm; gives `(harvests, whether the bot is too full)` |
| `switch()` | none | a farm to go on with instead: its result, which `farm` gives, or `None` |
| `learn(ticks, n)` | none | `n` harvests of a round took `ticks` game ticks |
| `full(boxes, told)` | none | what a full farm does each round |
| `idle(boxes)` | none | what a round that harvested nothing does before the bot walks to its corner and waits |

## `run(f, name, key, at, steps, radius=32)`

Sets up the farm `name` of spec `f` and runs its `steps` with [`lib.project.run`](project.md), so a stopped run goes on at the step it was on. Its corner is `at`, else the one `memory[f["corners"]][key]` keeps, else where the bot stands; an `at` other than the kept one moves the farm there (#818): it says `farm moved to x y z`, forgets the old cells, depot and project and lays the farm out afresh, and once the new depot is laid out carries the old depot's stock into it ([`carry`](#carryname-old-radius), #823). The cells go east and south from the block under the corner ([`lib.cells.layout`](cells.md)), the depot north of them, a path's width off, going east; home is the path by the depot, so a bot that dies comes back at the farm and [`lib.trees.recover`](trees.md#recover) fetches what it dropped. A new or moved farm whose corner fits fewer growing cells than a corner nearby says so ([`hint`](#hintf-name-at)).

- **Returns** `lib.project.run`'s `result()` once a step fails, after saying `name stopped: <reason> at <step>`, with `missing <item> xN` for what the step lacks. A farm step never returns otherwise.
- **Pauses** yes.

## `farm(f, name, size, radius)`

Farms forever. Each round it eats ([`lib.food.upkeep`](food.md)), does the spec's `chores`, then harvests every ripe cell (once the bot has the spec's `room()`, else it stores first, and where it still has too little says once `inventory full, store: <reason>; felling stops` and harvests nothing, #765), stores what it carries in the depot (each round, #766), does the spec's `stock` and `tend`; then the spec's `switch`. It builds the next cell where the farm is below [`limit`](#limitf-name-size) and [`stocked`](#stockedf-radius) gets what one takes, else says once `next cell: <reason>, lacks {...}` and tries again `BACKOFF` (10) rounds later, or in the next round that harvested. Once it has all its cells it says `farm full` and does the spec's `full` each round. Where it harvested nothing, it does the spec's `idle`, walks into its corner's chunk ([`corner`](#cornername), #814) and waits `WAIT` (100) ticks. Each round takes back the scaffold its walks placed ([`lib.trees.take_back`](trees.md#take_backkeep)).

The harvest, store and tending go over the cells the bot can read where it stands, then over those it reads at one cell it cannot, the next each round, which it walks to ([`visit`](#visitf-b), #811), so the corner's cells are farmed every round (#819). With `size`, the farm grows to `size` cells; with `None`, to `limit`.

- **Pauses** yes.

## `build(f, name, size, radius)`

Builds the next cell of the farm `name` ([`following`](#followingf-name-size)): gets the fill [`lib.terrain.needs`](terrain.md) names for its floor's holes up to the spec's `height`, on top of the materials' fill, with the spec's `gets` (#821), the tools it names and the spec's `tools` with [`ensure_tool`](get.md), takes back the scaffold earlier walks placed, then the spec's `build_cell`. Where that is ok it marks the cell built ([`lib.cells.add`](cells.md)) and says `built cell N` and `cells N/M`.

- **Returns** `result()`: ok, or the failed get's or `build_cell`'s.
- **Pauses** yes.

## `stocked(f, radius)`

Makes the bot carry the spec's `materials()`, each by the spec's `gets` (#643).

- **Returns** `result()`: ok, or the failed get's for the first item it could not get.
- **Pauses** yes.

## `ticking(f, name)`

The indices of the farm `name`'s cells whose spots (the spec's `spots`) all lie in the chunks that get random ticks while the bot stands at the farm's corner, cell 0's low corner: its chunk and those within `TICKING` (1) of it (#812). A bot's chunk ticket gives its chunk simulation level 30 and each chunk out one more, and the game random-ticks only the chunks at entity-ticking level, 31 or less, so a cell farther off grows only while the bot is near it. Of the first 32 cells; worked out once a run. Done at once.

## `ticking_at(f, name, corner)`

`ticking`'s cells were cell 0's low corner at `corner` instead. Done at once.

## `hint(f, name, at)`

Says where a corner fits more cells that keep growing than `at` (#813), such as `a corner at 32 64 -16 fits 20 growing cells, this one 9`: the north-west block of `at`'s chunk or of the chunk east, south or south-east of it, whichever is nearest, as each fits the most. Nothing where `at` is such a corner already. Done at once.

## `limit(f, name, size)`

The most cells of the farm: `size`, else the spec's `most()` but no more than its cells that keep growing (`ticking`). Done at once.

## `built(f, name, size)`

How many cells of the farm count toward `limit`: all built, else with no `size` those built that keep growing (`ticking`), so cells outside them built under a size leave its room (#815). Done at once.

## `following(f, name, size)`

The index of the cell `build` builds next: [`lib.cells.next`](cells.md), else with no `size` the lowest cell that keeps growing not built. Kept as `"next"` in `memory["cells"][name]`, so [`lib.treefarm.outside_cells`](treefarm.md) keeps dirt gets off it (#816). Done at once.

## `visit(f, b)`

Walks to cell `b`, a `(low, high)` box, where the bot cannot read it (the spec's `loaded`): to the block over its low corner, as `goto` loads the chunks on its way (#811). A bot keeps only the chunks within 3 of its own loaded.

- **Pauses** yes.

## `corner(name)`

Walks the bot into the chunk of the farm `name`'s corner, cell 0's low corner, where it stands elsewhere (#814), so the cells `ticking` counts get random ticks while it waits.

- **Pauses** yes.

## `carry(name, old, radius)`

Empties the chests `old`, a moved farm's old depot, into the depot `name`, in as many trips as the bot's room takes, keeping what the bot carried itself (#823). Where a trip takes nothing but the old chests still hold some, such as a full depot, it says `left N items in the old depot` and goes on.

- **Pauses** yes.

## `tell(told, what)`

Says `what` once: not where `told`, a dict of what was said, holds it already. Done at once.

A script, or a test, can swap one of these by assigning it, as `lib.cellfarm.wait = counted` for the round's wait, or `lib.cellfarm.stocked = ...` to keep a farm from building.
